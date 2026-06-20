package com.cloudcentinel.parkingapp.organization;

import java.time.Instant;
import java.util.UUID;

public record Organization(
        UUID id,
        String orgName,
        String rutCompany,
        String orgEmail,
        String phoneNumber,
        String orgStatus,
        UUID adminUserId,
        Instant createdAt
) {}
