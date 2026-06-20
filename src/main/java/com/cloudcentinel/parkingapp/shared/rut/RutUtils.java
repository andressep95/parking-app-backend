package com.cloudcentinel.parkingapp.shared.rut;

import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;

/**
 * Utilidades para normalización y validación de RUT chileno.
 *
 * <p>El RUT es el nombre de usuario en Cognito. Antes de cualquier operación
 * que lo use como credencial (login, creación de usuario), debe normalizarse
 * a la forma canónica {@code "XXXXXXXX-D"} y validarse con módulo 11.</p>
 *
 * <p>Formatos de entrada aceptados:</p>
 * <ul>
 *   <li>{@code "12.345.678-9"} — con puntos y guión</li>
 *   <li>{@code "12345678-9"} — sin puntos, con guión</li>
 *   <li>{@code "123456789"} — sin separadores (último dígito es el DV)</li>
 * </ul>
 *
 * <p>Clase de utilidad: constructor privado, todos los métodos son estáticos.</p>
 */
public final class RutUtils {

    private RutUtils() {}

    /**
     * Normaliza un RUT en cualquier formato a la forma canónica {@code "XXXXXXXX-D"}.
     *
     * <p>El resultado en mayúsculas garantiza consistencia cuando el dígito
     * verificador es {@code K}: Cognito almacena el username como se registró,
     * así que siempre se pasa en mayúsculas.</p>
     *
     * @param raw RUT en cualquier formato válido.
     * @return RUT en forma canónica, ej: {@code "12345678-9"} o {@code "9876543-K"}.
     * @throws BadRequestException si el formato no puede parsearse.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("rut_invalido");
        }

        String s = raw.toUpperCase().strip()
                .replace(".", "")
                .replace(" ", "");

        if (s.length() < 2) {
            throw new BadRequestException("rut_invalido");
        }

        String body;
        String dv;

        int dash = s.indexOf('-');
        if (dash != -1) {
            body = s.substring(0, dash);
            dv   = s.substring(dash + 1);
        } else {
            body = s.substring(0, s.length() - 1);
            dv   = s.substring(s.length() - 1);
        }

        if (!isDigits(body) || body.isEmpty()) {
            throw new BadRequestException("rut_invalido");
        }
        if (dv.length() != 1 || (!dv.equals("K") && !isDigits(dv))) {
            throw new BadRequestException("rut_invalido");
        }

        return body + "-" + dv;
    }

    /**
     * Valida el dígito verificador de un RUT ya normalizado usando módulo 11.
     *
     * <p>Debe llamarse después de {@link #normalize(String)}. Si el RUT no está
     * normalizado el resultado es indefinido.</p>
     *
     * @param normalized RUT en forma canónica {@code "XXXXXXXX-D"}.
     * @return {@code true} si el dígito verificador es correcto.
     */
    public static boolean isValid(String normalized) {
        String[] parts = normalized.split("-", 2);
        if (parts.length != 2) return false;

        String body = parts[0];
        String dv   = parts[1];

        int sum  = 0;
        int mult = 2;
        for (int i = body.length() - 1; i >= 0; i--) {
            int digit = body.charAt(i) - '0';
            sum += digit * mult;
            mult = (mult == 7) ? 2 : mult + 1;
        }

        int remainder = 11 - (sum % 11);
        String expected = switch (remainder) {
            case 11 -> "0";
            case 10 -> "K";
            default -> String.valueOf(remainder);
        };

        return dv.equals(expected);
    }

    /**
     * Normaliza y valida el RUT en un solo paso.
     *
     * <p>Método de conveniencia para los services que necesitan ambas operaciones
     * juntas. Lanza {@link BadRequestException} tanto si el formato es inválido
     * como si el dígito verificador no coincide.</p>
     *
     * @param raw RUT en cualquier formato.
     * @return RUT en forma canónica {@code "XXXXXXXX-D"}.
     * @throws BadRequestException si el RUT no puede normalizarse o el DV es incorrecto.
     */
    public static String normalizeAndValidate(String raw) {
        String normalized = normalize(raw);
        if (!isValid(normalized)) {
            throw new BadRequestException("rut_invalido");
        }
        return normalized;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
