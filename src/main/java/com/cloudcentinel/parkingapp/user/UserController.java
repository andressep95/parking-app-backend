package com.cloudcentinel.parkingapp.user;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<UserResponse> createUser(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(userService.createUser(jwt, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<List<UserResponse>> listUsers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID orgId) {
        return ResponseEntity.ok(userService.listUsers(jwt, orgId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUser(jwt, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(jwt, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Map<String, String>> activateUser(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("userStatus", userService.setUserStatus(id, true)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<Map<String, String>> deactivateUser(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("userStatus", userService.setUserStatus(id, false)));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return ResponseEntity.ok().build();
    }
}
