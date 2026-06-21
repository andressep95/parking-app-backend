package com.cloudcentinel.parkingapp.tariff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Tariff(
        UUID       id,
        UUID       locationId,
        String     vehicleType,
        String     name,
        BigDecimal pricePerHour,
        BigDecimal minimumCharge,
        int        graceMinutes,
        boolean    isActive,
        Instant    validFrom,
        Instant    validUntil
) {}
