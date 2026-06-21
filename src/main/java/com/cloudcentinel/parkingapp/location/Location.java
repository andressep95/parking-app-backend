package com.cloudcentinel.parkingapp.location;

import java.time.Instant;
import java.util.UUID;

public record Location(
        UUID    id,
        UUID    orgId,
        String  locationName,
        String  address,
        String  city,
        int     capacity,
        String  timezone,
        String  locationStatus,
        Instant createdAt
) {}
