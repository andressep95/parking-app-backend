package com.cloudcentinel.parkingapp.pos;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BootstrapResponse(
        BootstrapTerminal      terminal,
        BootstrapOrganization  organization,
        BootstrapLocation      location,
        List<BootstrapTariff>  tariffs,
        List<BootstrapSession> activeSessions,
        BootstrapShift         currentShift
) {
    public record BootstrapTerminal(UUID id, String serialNumber, String model) {}

    public record BootstrapOrganization(UUID id, String name, String rut,
                                        String email, String phone) {}

    public record BootstrapLocation(UUID id, String name, String address,
                                    String city, String timezone, int capacity) {}

    public record BootstrapTariff(UUID id, String vehicleType, String name,
                                  BigDecimal pricePerHour, BigDecimal minimumCharge,
                                  int graceMinutes) {}

    public record BootstrapSession(UUID id, String plate, String vehicleType,
                                   long entryAt, String operatorName) {}

    public record BootstrapShift(UUID id, long startedAt, BigDecimal openingCash) {}
}
