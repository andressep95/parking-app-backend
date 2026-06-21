package com.cloudcentinel.parkingapp.parking;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutRequest(
        UUID       transactionId,
        Long       exitAt,
        Integer    durationMinutes,
        BigDecimal calculatedAmount,
        String     paymentMethod,
        String     tuuReference
) {}
