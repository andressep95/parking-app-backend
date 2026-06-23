package com.cloudcentinel.parkingapp.terminal;

public record UpdateTerminalRequest(
        String model,
        String appVersion
) {
    public boolean hasAnyField() {
        return model != null || appVersion != null;
    }
}
