package com.cloudcentinel.parkingapp.parking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ParkingSessionResponse(
        UUID           id,
        String         plate,
        String         vehicleType,
        String         status,
        Instant        entryAt,
        Instant        exitAt,
        Integer        durationMinutes,
        BigDecimal     calculatedAmount,
        UUID           locationId,
        UUID           operatorId,
        UUID           terminalId,
        UUID           shiftId,
        TariffSnapshot tariffSnapshot,
        UUID           transactionId,
        String         paymentMethod
) {}
