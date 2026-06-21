package com.cloudcentinel.parkingapp.parking;

import java.util.UUID;

public record CreateParkingSessionRequest(
        UUID          id,
        String        plate,
        String        vehicleType,
        Long          entryAt,
        UUID          shiftId,
        TariffSnapshot tariffSnapshot
) {}
