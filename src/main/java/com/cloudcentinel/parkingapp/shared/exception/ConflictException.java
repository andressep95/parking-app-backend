package com.cloudcentinel.parkingapp.shared.exception;

/**
 * Lanzada cuando la operación viola una restricción de unicidad o un invariante
 * de negocio que impide proceder con el estado actual del sistema.
 *
 * <p>El {@link GlobalExceptionHandler} la intercepta y devuelve
 * {@code HTTP 409 Conflict}.</p>
 *
 * <p>Ejemplos de uso:</p>
 * <ul>
 *   <li>RUT ya registrado en Cognito o en la tabla {@code users}</li>
 *   <li>Email duplicado</li>
 *   <li>El operador ya tiene un turno {@code ACTIVE} abierto</li>
 *   <li>El operador ya tiene una sesión activa en otro terminal</li>
 * </ul>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
