package com.cloudcentinel.parkingapp.tariff;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTariffRequest(
        UUID       locationId,
        String     vehicleType,
        String     name,
        BigDecimal pricePerHour,
        BigDecimal minimumCharge,
        Integer    graceMinutes
) {}
