package com.cloudcentinel.parkingapp.auth;

/**
 * Respuesta del endpoint {@code POST /auth/device} con los tokens de Cognito.
 *
 * <p>El {@code accessToken} es el JWT que el POS debe incluir en el header
 * {@code Authorization: Bearer <token>} de todas las llamadas subsiguientes.
 * Caduca en 1 hora (configurado en el user pool).</p>
 *
 * <p>El {@code refreshToken} permite renovar el {@code accessToken} sin
 * pedir credenciales de nuevo. El POS lo almacena de forma segura en el
 * dispositivo. Si el operador cierra sesión o un ADMIN revoca la sesión
 * remotamente, el {@code refreshToken} queda invalidado en Cognito y la
 * renovación falla con {@code NotAuthorizedException}.</p>
 *
 * <p>El {@code idToken} contiene atributos del perfil del usuario (nombre,
 * email). El POS lo usa solo para display; nunca debe enviarse al backend
 * como credencial de autenticación.</p>
 *
 * @param accessToken  JWT firmado por Cognito. Bearer token para las APIs del backend.
 * @param refreshToken Token de larga duración para renovar el access token.
 * @param idToken      JWT con atributos del perfil del usuario. Solo para uso del cliente.
 * @param expiresIn    Segundos hasta la expiración del {@code accessToken} (típicamente 3600).
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String idToken,
        int expiresIn
) {}
