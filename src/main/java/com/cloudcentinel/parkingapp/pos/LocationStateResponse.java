package com.cloudcentinel.parkingapp.pos;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record LocationStateResponse(
        long              asOf,
        List<NewSession>  newSessions,
        List<ClosedSession> closedSessions
) {
    public record NewSession(UUID id, String plate, String vehicleType,
                             long entryAt, String operatorName) {}

    public record ClosedSession(UUID id, String plate, long exitAt,
                                String status, BigDecimal amount) {}
}
