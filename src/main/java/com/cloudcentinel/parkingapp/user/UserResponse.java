package com.cloudcentinel.parkingapp.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String cognitoSub,
        String rut,
        String email,
        String givenName,
        String familyName,
        String phoneNumber,
        String role,
        String userStatus,
        UUID orgId,
        UUID locationId,
        Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(
                u.id(), u.cognitoSub(), u.rut(), u.emailAddr(),
                u.givenName(), u.familyName(), u.phoneNumber(),
                u.role(), u.userStatus(), u.orgId(), u.locationId(), u.createdAt()
        );
    }
}
