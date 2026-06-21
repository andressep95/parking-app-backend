package com.cloudcentinel.parkingapp.location;

import com.cloudcentinel.parkingapp.organization.OrganizationRepository;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class LocationService {

    private final LocationRepository     locations;
    private final OrganizationRepository organizations;
    private final UserRepository         users;

    public LocationService(LocationRepository locations,
                           OrganizationRepository organizations,
                           UserRepository users) {
        this.locations     = locations;
        this.organizations = organizations;
        this.users         = users;
    }

    public Location createLocation(CreateLocationRequest req) {
        if (req.orgId() == null || req.locationName() == null
                || req.address() == null || req.city() == null || req.capacity() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if (req.capacity() < 1) throw new BadRequestException("capacity_invalido");

        organizations.findById(req.orgId())
                .orElseThrow(() -> new NotFoundException("organizacion_no_encontrada"));

        String timezone = req.timezone() != null ? req.timezone() : "America/Santiago";
        return locations.insert(req.orgId(), req.locationName(), req.address(),
                req.city(), req.capacity(), timezone);
    }

    public List<Location> listLocations(Jwt jwt, UUID orgId) {
        if (isAdmin(jwt)) {
            return orgId != null ? locations.findByOrgId(orgId) : locations.findAll();
        }
        UUID callerOrgId = loadCaller(jwt).orgId();
        if (orgId != null && !orgId.equals(callerOrgId)) throw new ForbiddenException("sin_permiso");
        return locations.findByOrgId(callerOrgId);
    }

    public Location getLocation(Jwt jwt, UUID id) {
        Location loc = locations.findById(id)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());
        return loc;
    }

    public Location updateLocation(Jwt jwt, UUID id, UpdateLocationRequest req) {
        if (!req.hasAnyField()) throw new BadRequestException("sin_campos_para_actualizar");
        if (req.capacity() != null && req.capacity() < 1) throw new BadRequestException("capacity_invalido");

        Location loc = locations.findById(id)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());

        locations.update(id, req.locationName(), req.address(), req.city(),
                req.capacity(), req.timezone());
        return locations.findById(id).orElseThrow();
    }

    public void deleteLocation(UUID id) {
        locations.findById(id)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        if (locations.hasTerminals(id))
            throw new ConflictException("locacion_tiene_terminales");
        if (locations.hasActiveParkingSessions(id))
            throw new ConflictException("locacion_tiene_sesiones_activas");
        locations.delete(id);
    }

    public String setLocationStatus(Jwt jwt, UUID id, boolean active) {
        Location loc = locations.findById(id)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());
        String status = active ? "ACTIVE" : "INACTIVE";
        locations.setStatus(id, status);
        return status;
    }

    private void assertAccess(Jwt jwt, UUID locationOrgId) {
        if (isAdmin(jwt)) return;
        if (!locationOrgId.equals(loadCaller(jwt).orgId())) throw new ForbiddenException("sin_permiso");
    }

    private boolean isAdmin(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        return groups != null && groups.contains("ADMIN");
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
