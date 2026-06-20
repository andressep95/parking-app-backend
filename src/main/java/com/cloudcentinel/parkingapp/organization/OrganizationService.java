package com.cloudcentinel.parkingapp.organization;

import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.shared.rut.RutUtils;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final UserRepository         users;

    public OrganizationService(OrganizationRepository organizations, UserRepository users) {
        this.organizations = organizations;
        this.users         = users;
    }

    public Organization createOrganization(CreateOrganizationRequest req) {
        if (req.orgName() == null || req.rutCompany() == null || req.orgEmail() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        String rutCompany = RutUtils.normalizeAndValidate(req.rutCompany());

        if (organizations.existsByRutCompany(rutCompany)) {
            throw new ConflictException("rut_empresa_ya_registrado");
        }
        if (req.adminUserId() != null) {
            User adminUser = users.findById(req.adminUserId())
                    .orElseThrow(() -> new BadRequestException("admin_user_invalido"));
            if (!"CUSTOMER".equals(adminUser.role())) {
                throw new BadRequestException("admin_user_invalido");
            }
        }

        Organization org = organizations.insert(req.orgName(), rutCompany, req.orgEmail(),
                req.phoneNumber(), req.adminUserId());

        if (req.adminUserId() != null) {
            users.setOrgId(req.adminUserId(), org.id());
        }
        return org;
    }

    public List<Organization> listOrganizations() {
        return organizations.findAll();
    }

    public Organization getOrganization(Jwt jwt, UUID id) {
        Organization org = organizations.findById(id)
                .orElseThrow(() -> new NotFoundException("organizacion_no_encontrada"));

        if (isCustomerOnly(jwt)) {
            User caller = loadCaller(jwt);
            if (!id.equals(caller.orgId())) throw new ForbiddenException("sin_permiso");
        }
        return org;
    }

    public Organization updateOrganization(Jwt jwt, UUID id, UpdateOrganizationRequest req) {
        if (!req.hasAnyField()) throw new BadRequestException("sin_campos_para_actualizar");

        Organization org = organizations.findById(id)
                .orElseThrow(() -> new NotFoundException("organizacion_no_encontrada"));

        if (isCustomerOnly(jwt)) {
            User caller = loadCaller(jwt);
            if (!id.equals(caller.orgId())) throw new ForbiddenException("sin_permiso");
        }

        // Validate new RUT if provided
        String rutCompany = null;
        if (req.rutCompany() != null) {
            rutCompany = RutUtils.normalizeAndValidate(req.rutCompany());
            if (!rutCompany.equals(org.rutCompany()) && organizations.existsByRutCompany(rutCompany)) {
                throw new ConflictException("rut_empresa_ya_registrado");
            }
        }

        organizations.update(id, req.orgName(), rutCompany, req.orgEmail(), req.phoneNumber());
        return organizations.findById(id).orElseThrow();
    }

    @Transactional
    public void deleteOrganization(UUID id) {
        organizations.findById(id)
                .orElseThrow(() -> new NotFoundException("organizacion_no_encontrada"));

        if (organizations.hasOnlineTerminals(id)) {
            throw new ConflictException("organizacion_tiene_terminales_activas");
        }

        users.clearOrgId(id);
        organizations.delete(id);
    }

    public String setOrganizationStatus(UUID id, boolean active) {
        organizations.findById(id)
                .orElseThrow(() -> new NotFoundException("organizacion_no_encontrada"));

        String status = active ? "ACTIVE" : "INACTIVE";
        organizations.setStatus(id, status);
        return status;
    }

    // Returns true when the caller has ONLY the CUSTOMER role (not ADMIN)
    private boolean isCustomerOnly(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        if (groups == null) return false;
        return groups.contains("CUSTOMER") && !groups.contains("ADMIN");
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
