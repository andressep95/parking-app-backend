package com.cloudcentinel.parkingapp.user;

import java.util.UUID;

public record CreateUserRequest(
        String rut,
        String password,
        String email,
        String givenName,
        String familyName,
        String phoneNumber,
        String role,
        UUID orgId,
        UUID locationId
) {}
