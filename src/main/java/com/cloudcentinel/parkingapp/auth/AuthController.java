package com.cloudcentinel.parkingapp.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoints de autenticación y gestión de sesiones.
 *
 * <h2>Acceso por endpoint</h2>
 * <ul>
 *   <li>{@code POST /auth/device} — público (permit all). El POS envía sus
 *       credenciales aquí; no puede tener un JWT previo porque es el proceso
 *       de login.</li>
 *   <li>{@code POST /auth/logout} — requiere JWT válido. El operador cierra
 *       su propia sesión.</li>
 *   <li>{@code DELETE /auth/sessions/{userId}} — requiere rol {@code ADMIN}.
 *       Cierre remoto de sesión de cualquier usuario.</li>
 * </ul>
 *
 * <h2>IP del cliente</h2>
 * <p>La IP se extrae de {@link HttpServletRequest#getRemoteAddr()} y se
 * almacena en {@code user_sessions.ip_address} para auditoría. Si el backend
 * está detrás de un reverse proxy (nginx, ALB), configurar
 * {@code server.forward-headers-strategy=native} en {@code application.properties}
 * para que Spring lea el header {@code X-Forwarded-For} correctamente.</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Login mediado para terminales POS Android.
     *
     * <p>El POS envía RUT, contraseña y número de serie. El backend valida
     * el terminal, autentica contra Cognito y registra la sesión. Devuelve
     * los tres tokens de Cognito ({@code access}, {@code refresh}, {@code id}).</p>
     *
     * <p>Este endpoint está configurado como {@code permitAll()} en
     * {@code SecurityConfig}: no requiere JWT previo porque es el proceso de
     * obtención de tokens.</p>
     *
     * @param request     cuerpo con RUT, password y serialNumber.
     * @param httpRequest request HTTP de Spring MVC, usado para extraer la IP del cliente.
     * @return {@code 200 OK} con {@link TokenResponse}.
     */
    @PostMapping("/device")
    public ResponseEntity<TokenResponse> deviceLogin(
            @RequestBody DeviceLoginRequest request,
            HttpServletRequest httpRequest) {

        TokenResponse tokens = authService.deviceLogin(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(tokens);
    }

    /**
     * Cierra la sesión del caller: invalida el refresh token en Cognito y
     * elimina la fila de {@code user_sessions}.
     *
     * <p>{@code @AuthenticationPrincipal Jwt} es inyectado por Spring Security
     * después de validar el bearer token del header {@code Authorization}.
     * {@code jwt.getSubject()} retorna el {@code sub} de Cognito, que es la
     * FK hacia {@code users.cognito_sub}.</p>
     *
     * @param jwt JWT validado por Spring Security, inyectado automáticamente.
     * @return {@code 204 No Content} al cerrar la sesión exitosamente.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        String bearerToken = extractBearerToken(httpRequest);
        authService.logout(jwt.getSubject(), bearerToken);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fuerza el cierre de sesión de cualquier usuario. Solo para ADMIN.
     *
     * <p>{@code @PreAuthorize("hasRole('ADMIN')")} verifica que el JWT del caller
     * contenga {@code ADMIN} en el claim {@code cognito:groups} antes de
     * ejecutar el método. Si no lo tiene, Spring Security rechaza con 403
     * sin llegar al service.</p>
     *
     * @param userId UUID interno del usuario cuya sesión se cierra.
     * @return {@code 204 No Content} al revocar la sesión exitosamente.
     */
    @DeleteMapping("/sessions/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> adminCloseSession(@PathVariable UUID userId) {
        authService.adminCloseSession(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extrae el bearer token del header {@code Authorization} para pasarlo a
     * {@code GlobalSignOut}, que requiere el access token del propio usuario.
     *
     * <p>Spring Security ya validó el token antes de llegar aquí, así que el
     * header siempre existe y tiene el prefijo {@code "Bearer "}.</p>
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : "";
    }
}
