package com.cloudcentinel.parkingapp.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID       parkingSessionId,
        UUID       transactionId,
        String     plate,
        String     vehicleType,
        String     status,
        Instant    entryAt,
        Instant    exitAt,
        Integer    durationMinutes,
        BigDecimal amount,
        String     paymentMethod,
        String     tuuReference,
        Instant    transactionAt
) {}
