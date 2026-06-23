package com.cloudcentinel.parkingapp.terminal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/terminals")
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Terminal> registerTerminal(@RequestBody CreateTerminalRequest request) {
        return ResponseEntity.status(201).body(terminalService.registerTerminal(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<List<Terminal>> listTerminals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID orgId) {
        return ResponseEntity.ok(terminalService.listTerminals(jwt, orgId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Terminal> getTerminal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ResponseEntity.ok(terminalService.getTerminal(jwt, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Terminal> updateTerminal(
            @PathVariable UUID id,
            @RequestBody UpdateTerminalRequest request) {
        return ResponseEntity.ok(terminalService.updateTerminal(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteTerminal(@PathVariable UUID id) {
        terminalService.deleteTerminal(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/maintenance")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> setMaintenance(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("status", terminalService.setMaintenanceStatus(id, "MAINTENANCE")));
    }

    @PostMapping("/{id}/offline")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> setOffline(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("status", terminalService.setMaintenanceStatus(id, "OFFLINE")));
    }
}
