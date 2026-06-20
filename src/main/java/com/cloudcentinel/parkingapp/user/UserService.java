package com.cloudcentinel.parkingapp.user;

import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.shared.cognito.CognitoAdminClient;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.shared.rut.RutUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository    users;
    private final CognitoAdminClient cognito;
    private final SessionRepository sessions;

    public UserService(UserRepository users, CognitoAdminClient cognito, SessionRepository sessions) {
        this.users    = users;
        this.cognito  = cognito;
        this.sessions = sessions;
    }

    public UserResponse createUser(Jwt jwt, CreateUserRequest req) {
        boolean callerIsAdmin    = hasRole(jwt, "ADMIN");
        boolean callerIsCustomer = hasRole(jwt, "CUSTOMER");

        if (req.role() == null || !List.of("ADMIN", "CUSTOMER", "OPERATOR").contains(req.role())) {
            throw new BadRequestException("rol_invalido");
        }
        // CUSTOMER can only create OPERATOR
        if (callerIsCustomer && !callerIsAdmin && !"OPERATOR".equals(req.role())) {
            throw new ForbiddenException("sin_permiso");
        }
        // CUSTOMER must use their own orgId
        if (callerIsCustomer && !callerIsAdmin) {
            User caller = loadCaller(jwt);
            if (req.orgId() == null || !req.orgId().equals(caller.orgId())) {
                throw new ForbiddenException("org_id_no_coincide");
            }
        }
        if (req.rut() == null || req.email() == null || req.password() == null
                || req.givenName() == null || req.familyName() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if ("OPERATOR".equals(req.role()) && req.locationId() == null) {
            throw new BadRequestException("location_id_requerido_para_operator");
        }

        String rut = RutUtils.normalizeAndValidate(req.rut());

        if (users.existsByRut(rut)) throw new ConflictException("rut_ya_registrado");
        if (users.existsByEmail(req.email())) throw new ConflictException("email_ya_registrado");

        String cognitoSub;
        try {
            cognitoSub = cognito.createUser(rut, req.password(), req.email(),
                    req.givenName(), req.familyName(), req.phoneNumber());
            cognito.addUserToGroup(rut, req.role());
        } catch (UsernameExistsException e) {
            throw new ConflictException("rut_ya_registrado");
        }

        try {
            User created = users.insert(cognitoSub, rut, req.email(), req.givenName(),
                    req.familyName(), req.phoneNumber(), req.role(), req.orgId(), req.locationId());
            return UserResponse.from(created);
        } catch (Exception e) {
            cognito.deleteUser(rut);
            throw e;
        }
    }

    public List<UserResponse> listUsers(Jwt jwt, UUID orgId) {
        boolean isAdmin    = hasRole(jwt, "ADMIN");
        boolean isCustomer = hasRole(jwt, "CUSTOMER");

        if (!isAdmin && !isCustomer) throw new ForbiddenException("sin_permiso");

        if (isCustomer && !isAdmin) {
            if (orgId == null) throw new BadRequestException("org_id_requerido");
            User caller = loadCaller(jwt);
            if (!orgId.equals(caller.orgId())) throw new ForbiddenException("sin_permiso");
            return users.findByOrgId(orgId).stream().map(UserResponse::from).toList();
        }

        return (orgId != null ? users.findByOrgId(orgId) : users.findAll())
                .stream().map(UserResponse::from).toList();
    }

    public UserResponse getUser(Jwt jwt, UUID id) {
        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        // OPERATOR can only see themselves; ADMIN and CUSTOMER see anyone
        if (isOnlyOperator(jwt)) {
            User caller = loadCaller(jwt);
            if (!caller.id().equals(target.id())) throw new ForbiddenException("sin_permiso");
        }
        return UserResponse.from(target);
    }

    public UserResponse updateUser(Jwt jwt, UUID id, UpdateUserRequest req) {
        if (!req.hasAnyField()) throw new BadRequestException("sin_campos_para_actualizar");

        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        if (isOnlyOperator(jwt)) {
            User caller = loadCaller(jwt);
            if (!caller.id().equals(target.id())) throw new ForbiddenException("sin_permiso");
        }

        users.update(id, req.email(), req.givenName(), req.familyName(),
                req.phoneNumber(), req.locationId());
        return UserResponse.from(users.findById(id).orElseThrow());
    }

    public void deleteUser(UUID id) {
        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        sessions.deleteByUserId(target.id());
        users.delete(id);
        cognito.deleteUser(target.rut());
    }

    public String setUserStatus(UUID id, boolean active) {
        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        String status = active ? "ACTIVE" : "INACTIVE";
        users.setStatus(id, status);
        if (active) {
            cognito.enableUser(target.rut());
        } else {
            cognito.disableUser(target.rut());
        }
        return status;
    }

    public void resetPassword(UUID id, ResetPasswordRequest req) {
        if (req.newPassword() == null || req.newPassword().isBlank()) {
            throw new BadRequestException("new_password_requerido");
        }
        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
        cognito.setUserPassword(target.rut(), req.newPassword());
    }

    // Returns true when the caller has ONLY the OPERATOR role (not ADMIN or CUSTOMER)
    private boolean isOnlyOperator(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        if (groups == null) return false;
        return groups.contains("OPERATOR") && !groups.contains("ADMIN") && !groups.contains("CUSTOMER");
    }

    private boolean hasRole(Jwt jwt, String role) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        return groups != null && groups.contains(role);
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
