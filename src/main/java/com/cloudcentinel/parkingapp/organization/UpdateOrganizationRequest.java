package com.cloudcentinel.parkingapp.organization;

public record UpdateOrganizationRequest(
        String orgName,
        String rutCompany,
        String orgEmail,
        String phoneNumber
) {
    public boolean hasAnyField() {
        return orgName != null || rutCompany != null || orgEmail != null || phoneNumber != null;
    }
}
