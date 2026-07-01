package com.cloudcentinel.parkingapp.parking;

import com.cloudcentinel.parkingapp.tariff.TariffBracket;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

public record TariffSnapshot(
        String              tariffType,
        BigDecimal          pricePerMinute,
        int                 graceMinutes,
        BigDecimal          maxCharge,
        BigDecimal          flatAmount,
        List<TariffBracket> brackets
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize TariffSnapshot", e);
        }
    }
}
