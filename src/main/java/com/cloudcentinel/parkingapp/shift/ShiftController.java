package com.cloudcentinel.parkingapp.shift;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/shifts")
public class ShiftController {

    private final ShiftService shiftService;

    public ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Shift> openShift(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody OpenShiftRequest request) {
        return ResponseEntity.status(201).body(shiftService.openShift(jwt, request));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Shift> closeShift(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody CloseShiftRequest request) {
        return ResponseEntity.ok(shiftService.closeShift(jwt, id, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<List<Shift>> listShifts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(shiftService.listShifts(jwt, operatorId, locationId, date, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Shift> getShift(@PathVariable UUID id) {
        return ResponseEntity.ok(shiftService.getShift(id));
    }
}
