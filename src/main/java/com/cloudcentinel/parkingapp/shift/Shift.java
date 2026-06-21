package com.cloudcentinel.parkingapp.shift;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Shift(
        UUID       id,
        UUID       operatorId,
        UUID       terminalId,
        UUID       locationId,
        String     status,
        Instant    startedAt,
        Instant    closedAt,
        BigDecimal openingCash,
        BigDecimal closingCash
) {}
