package com.cloudcentinel.parkingapp.organization;

import java.util.UUID;

public record CreateOrganizationRequest(
        String orgName,
        String rutCompany,
        String orgEmail,
        String phoneNumber,
        UUID adminUserId
) {}
