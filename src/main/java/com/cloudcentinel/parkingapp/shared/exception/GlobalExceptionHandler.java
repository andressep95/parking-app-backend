package com.cloudcentinel.parkingapp.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce excepciones de dominio a respuestas HTTP con formato RFC 7807
 * ({@link ProblemDetail}).
 *
 * <h2>Por qué RFC 7807</h2>
 * <p>ProblemDetail es el estándar de Spring Boot 3+ para errores HTTP. En lugar
 * de inventar un envelope propio {@code { "error": "...", "code": ... }}, usamos
 * el formato que cualquier cliente HTTP ya conoce:</p>
 * <pre>{@code
 * {
 *   "type":   "about:blank",
 *   "title":  "Not Found",
 *   "status": 404,
 *   "detail": "usuario_no_encontrado"
 * }
 * }</pre>
 *
 * <h2>Flujo</h2>
 * <p>Controller / Service lanza excepción de dominio →
 * este handler la intercepta → construye {@link ProblemDetail} →
 * Spring serializa a JSON con el status HTTP correcto.</p>
 *
 * <p>Solo se mapean las excepciones propias del dominio. Las excepciones de
 * Spring MVC (validación, binding, método no soportado) las maneja
 * {@code ResponseEntityExceptionHandler} del framework de forma automática.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * {@link BadRequestException} → {@code 400 Bad Request}.
     * Datos del cliente inválidos según reglas de negocio (ej: RUT con dígito
     * verificador incorrecto). Distinto de un error de validación de campo Bean
     * Validation, que Spring MVC convierte en 400 automáticamente.
     */
    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * {@link UnauthorizedException} → {@code 401 Unauthorized}.
     * Credenciales inválidas en el flujo de login mediado. El mensaje es siempre
     * genérico para no revelar si el usuario existe o la contraseña es incorrecta.
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * {@link NotFoundException} → {@code 404 Not Found}.
     * El {@code detail} lleva el mensaje de negocio (ej: {@code "usuario_no_encontrado"}).
     */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * {@link ConflictException} → {@code 409 Conflict}.
     * El {@code detail} identifica qué restricción se violó.
     */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * {@link ForbiddenException} → {@code 403 Forbidden}.
     * Se usa para violaciones de aislamiento de datos entre tenants,
     * no para fallos de autenticación (esos los maneja Spring Security con 401).
     */
    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }
}
