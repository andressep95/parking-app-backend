package com.cloudcentinel.parkingapp.transaction;

import com.cloudcentinel.parkingapp.location.Location;
import com.cloudcentinel.parkingapp.location.LocationRepository;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final LocationRepository    locations;
    private final UserRepository        users;

    public TransactionService(TransactionRepository transactions,
                              LocationRepository locations,
                              UserRepository users) {
        this.transactions = transactions;
        this.locations    = locations;
        this.users        = users;
    }

    public List<TransactionResponse> listByLocation(Jwt jwt, UUID locationId) {
        Location loc = locations.findById(locationId)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());
        return transactions.listByLocation(locationId);
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
