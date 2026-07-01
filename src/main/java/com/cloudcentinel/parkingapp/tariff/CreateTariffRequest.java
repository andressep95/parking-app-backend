package com.cloudcentinel.parkingapp.tariff;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateTariffRequest(
        UUID               locationId,
        String             vehicleType,
        String             tariffType,
        String             name,
        BigDecimal         pricePerMinute,
        Integer            graceMinutes,
        BigDecimal         maxCharge,
        BigDecimal         flatAmount,
        List<BracketRequest> brackets
) {
    public record BracketRequest(
            Integer    position,
            Integer    fromMinute,
            Integer    toMinute,
            BigDecimal pricePerMinute
    ) {}
}
