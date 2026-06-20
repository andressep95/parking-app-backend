package com.cloudcentinel.parkingapp.organization;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/organizations")
public class OrganizationController {

    private final OrganizationService orgService;

    public OrganizationController(OrganizationService orgService) {
        this.orgService = orgService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Organization> createOrganization(
            @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(201).body(orgService.createOrganization(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Organization>> listOrganizations() {
        return ResponseEntity.ok(orgService.listOrganizations());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Organization> getOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ResponseEntity.ok(orgService.getOrganization(jwt, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Organization> updateOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(orgService.updateOrganization(jwt, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteOrganization(@PathVariable UUID id) {
        orgService.deleteOrganization(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> activateOrganization(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("orgStatus", orgService.setOrganizationStatus(id, true)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> deactivateOrganization(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("orgStatus", orgService.setOrganizationStatus(id, false)));
    }
}
