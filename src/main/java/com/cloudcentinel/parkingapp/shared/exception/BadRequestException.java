package com.cloudcentinel.parkingapp.shared.exception;

/**
 * Lanzada cuando la petición del cliente contiene datos inválidos que impiden
 * procesar la operación antes de llegar a la base de datos o a Cognito.
 *
 * <p>El {@link GlobalExceptionHandler} la convierte en {@code HTTP 400 Bad Request}
 * con cuerpo RFC 7807. Es distinta de un error de validación de campo: aquí el
 * dato llegó correctamente formateado pero su valor es inválido según reglas de
 * negocio que no dependen del estado del sistema (ej: RUT con dígito verificador
 * incorrecto).</p>
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
