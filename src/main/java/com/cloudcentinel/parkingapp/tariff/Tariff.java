package com.cloudcentinel.parkingapp.tariff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Tariff(
        UUID               id,
        UUID               locationId,
        String             vehicleType,
        String             tariffType,
        String             name,
        BigDecimal         pricePerMinute,
        int                graceMinutes,
        BigDecimal         maxCharge,
        BigDecimal         flatAmount,
        List<TariffBracket> brackets,
        boolean            isActive,
        Instant            validFrom,
        Instant            validUntil
) {}
