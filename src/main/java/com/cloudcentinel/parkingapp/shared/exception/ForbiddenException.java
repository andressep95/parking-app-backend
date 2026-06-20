package com.cloudcentinel.parkingapp.shared.exception;

/**
 * Lanzada cuando el caller está autenticado pero no tiene permiso
 * sobre el recurso específico que solicita.
 *
 * <p>El {@link GlobalExceptionHandler} la intercepta y devuelve
 * {@code HTTP 403 Forbidden}.</p>
 *
 * <p>Esta excepción cubre el control de acceso a nivel de datos, no de rol.
 * Los chequeos de rol genéricos (ej: «solo ADMIN puede llamar este endpoint»)
 * los resuelve Spring Security con {@code @PreAuthorize} antes de que el
 * código de la aplicación llegue a ejecutarse.</p>
 *
 * <p>Ejemplos de uso:</p>
 * <ul>
 *   <li>Un CUSTOMER intenta leer usuarios de otra organización</li>
 *   <li>Un OPERATOR intenta cerrar el turno de otro operador</li>
 * </ul>
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
