package com.cloudcentinel.parkingapp.user;

import java.util.UUID;

public record UpdateUserRequest(
        String email,
        String givenName,
        String familyName,
        String phoneNumber,
        UUID locationId
) {
    public boolean hasAnyField() {
        return email != null || givenName != null || familyName != null
                || phoneNumber != null || locationId != null;
    }
}
