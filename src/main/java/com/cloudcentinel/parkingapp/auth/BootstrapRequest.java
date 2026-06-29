package com.cloudcentinel.parkingapp.auth;

public record BootstrapRequest(
        String rut,
        String password,
        String email,
        String givenName,
        String familyName,
        String phoneNumber
) {}
