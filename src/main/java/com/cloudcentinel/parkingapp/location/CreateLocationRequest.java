package com.cloudcentinel.parkingapp.location;

import java.util.UUID;

public record CreateLocationRequest(
        UUID    orgId,
        String  locationName,
        String  address,
        String  city,
        Integer capacity,
        String  timezone
) {}
