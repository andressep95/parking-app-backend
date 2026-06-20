package com.cloudcentinel.parkingapp.shared.exception;

/**
 * Lanzada cuando las credenciales proporcionadas son inválidas o el caller
 * no está autenticado para la operación solicitada.
 *
 * <p>El {@link GlobalExceptionHandler} la convierte en {@code HTTP 401 Unauthorized}.
 * Se usa exclusivamente en el flujo de login mediado ({@code POST /auth/device})
 * para los errores que devuelve Cognito ({@code UserNotFoundException},
 * {@code NotAuthorizedException}). En ambos casos el mensaje al cliente es
 * genérico ({@code "credenciales_invalidas"}) para no revelar si el usuario
 * existe o no.</p>
 *
 * <p>Los endpoints protegidos que reciben un JWT inválido o ausente devuelven
 * 401 automáticamente por Spring Security, sin pasar por esta excepción.</p>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
