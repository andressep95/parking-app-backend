package com.cloudcentinel.parkingapp.pos;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pos")
public class PosController {

    private final PosBootstrapService     bootstrapService;
    private final PosLocationStateService locationStateService;

    public PosController(PosBootstrapService bootstrapService,
                         PosLocationStateService locationStateService) {
        this.bootstrapService     = bootstrapService;
        this.locationStateService = locationStateService;
    }

    @GetMapping("/bootstrap/{serialNumber}")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BootstrapResponse> bootstrap(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String serialNumber) {
        return ResponseEntity.ok(bootstrapService.bootstrap(jwt, serialNumber));
    }

    @GetMapping("/location-state")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<LocationStateResponse> locationState(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long since) {
        return ResponseEntity.ok(locationStateService.getLocationState(jwt, since));
    }
}
