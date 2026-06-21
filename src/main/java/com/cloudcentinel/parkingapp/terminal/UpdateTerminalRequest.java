package com.cloudcentinel.parkingapp.terminal;

import java.util.UUID;

public record UpdateTerminalRequest(
        String model,
        UUID   locationId,
        String appVersion
) {
    public boolean hasAnyField() {
        return model != null || locationId != null || appVersion != null;
    }
}
