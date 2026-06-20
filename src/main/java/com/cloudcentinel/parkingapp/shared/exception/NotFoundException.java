package com.cloudcentinel.parkingapp.shared.exception;

/**
 * Lanzada cuando un recurso solicitado no existe en la base de datos.
 *
 * <p>El {@link GlobalExceptionHandler} la intercepta y devuelve
 * {@code HTTP 404} con cuerpo RFC 7807. Los controllers y services
 * no necesitan conocer el código de estado HTTP; solo lanzan esta
 * excepción con un mensaje descriptivo.</p>
 *
 * <p>Ejemplos de uso:</p>
 * <ul>
 *   <li>Usuario no encontrado por {@code cognito_sub}</li>
 *   <li>Organización no encontrada por {@code id}</li>
 *   <li>Terminal no registrada por {@code serial_number}</li>
 * </ul>
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
