package com.cloudcentinel.parkingapp.terminal;

import java.util.UUID;

public record CreateTerminalRequest(
        String serialNumber,
        String model,
        UUID   orgId,
        String appVersion
) {}
