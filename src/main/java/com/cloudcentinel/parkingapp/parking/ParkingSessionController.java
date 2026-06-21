package com.cloudcentinel.parkingapp.parking;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/parking-sessions")
public class ParkingSessionController {

    private final ParkingSessionService parkingService;

    public ParkingSessionController(ParkingSessionService parkingService) {
        this.parkingService = parkingService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<ParkingSessionResponse> createSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateParkingSessionRequest request) {
        ParkingSessionService.CreationResult result = parkingService.createSession(jwt, request);
        int httpStatus = result.created() ? 201 : 200;
        return ResponseEntity.status(httpStatus).body(result.session());
    }

    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<ParkingSessionResponse> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(parkingService.checkout(jwt, id, request));
    }
}
