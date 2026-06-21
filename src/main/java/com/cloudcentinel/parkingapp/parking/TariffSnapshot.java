package com.cloudcentinel.parkingapp.parking;

import java.math.BigDecimal;

public record TariffSnapshot(
        BigDecimal pricePerHour,
        BigDecimal minimumCharge,
        int        graceMinutes
) {
    public String toJson() {
        return String.format(
                "{\"pricePerHour\":%s,\"minimumCharge\":%s,\"graceMinutes\":%d}",
                pricePerHour.toPlainString(),
                minimumCharge.toPlainString(),
                graceMinutes
        );
    }
}
