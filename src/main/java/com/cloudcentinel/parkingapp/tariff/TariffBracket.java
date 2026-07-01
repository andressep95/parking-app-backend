package com.cloudcentinel.parkingapp.tariff;

import java.math.BigDecimal;
import java.util.UUID;

public record TariffBracket(
        UUID       id,
        UUID       tariffId,
        int        position,
        int        fromMinute,
        Integer    toMinute,
        BigDecimal pricePerMinute
) {}
