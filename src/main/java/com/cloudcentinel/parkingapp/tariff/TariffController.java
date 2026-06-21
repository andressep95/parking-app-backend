package com.cloudcentinel.parkingapp.tariff;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tariffs")
public class TariffController {

    private final TariffService tariffService;

    public TariffController(TariffService tariffService) {
        this.tariffService = tariffService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Tariff> createTariff(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateTariffRequest request) {
        return ResponseEntity.status(201).body(tariffService.createTariff(jwt, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<List<Tariff>> listTariffs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID locationId,
            @RequestParam(required = false, defaultValue = "true") Boolean active) {
        return ResponseEntity.ok(tariffService.listTariffs(jwt, locationId, active));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Tariff> getTariff(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ResponseEntity.ok(tariffService.getTariff(jwt, id));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Tariff> deactivateTariff(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ResponseEntity.ok(tariffService.deactivateTariff(jwt, id));
    }
}
