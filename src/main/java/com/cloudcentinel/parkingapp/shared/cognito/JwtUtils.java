package com.cloudcentinel.parkingapp.shared.cognito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidad para decodificar el payload de un JWT sin verificar la firma.
 *
 * <h2>Cuándo es seguro no verificar la firma</h2>
 * <p>Cuando el token fue emitido por Cognito en la misma llamada que estamos
 * procesando (respuesta directa de {@code AdminInitiateAuth}), ya confiamos
 * en él: lo acabamos de recibir de Cognito server-to-server, no del cliente
 * externo. Decodificar sin verificar es seguro en ese contexto y evita un
 * round-trip adicional al JWKS endpoint.</p>
 *
 * <p>Para tokens que llegan desde clientes externos (headers {@code Authorization}),
 * la verificación la realiza Spring Security automáticamente antes de que el
 * código de la aplicación los procese. Este utilitario nunca se usa en ese flujo.</p>
 *
 * <h2>Implementación</h2>
 * <p>Usa solo la stdlib de Java (Base64 + regex) para no introducir dependencias
 * adicionales. El payload de un JWT de Cognito es JSON plano — no JSON anidado
 * en los campos que nos interesan ({@code sub}, {@code cognito:groups}) — por
 * lo que el parsing con regex es suficiente y robusto.</p>
 */
public final class JwtUtils {

    private static final Pattern STRING_CLAIM =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

    private JwtUtils() {}

    /**
     * Extrae el claim {@code sub} (Cognito user sub) del payload de un JWT.
     *
     * <p>El {@code sub} es el identificador permanente del usuario en Cognito.
     * Es la FK que enlaza la tabla {@code users.cognito_sub} con el user pool.
     * Una vez creado no cambia aunque el usuario actualice su email o username.</p>
     *
     * @param accessToken JWT en formato {@code header.payload.signature}.
     * @return valor del claim {@code sub}.
     * @throws IllegalStateException si el token está malformado o no contiene {@code sub}.
     */
    public static String extractSub(String accessToken) {
        String sub = (String) extractClaims(accessToken).get("sub");
        if (sub == null || sub.isBlank()) {
            throw new IllegalStateException("El JWT no contiene el claim 'sub'");
        }
        return sub;
    }

    /**
     * Decodifica el payload del JWT y retorna todos los claims de tipo string
     * como mapa.
     *
     * <p>Solo extrae claims cuyo valor es un string JSON simple (no arrays ni
     * objetos anidados). Suficiente para {@code sub}, {@code username},
     * {@code token_use}, etc. Para el claim {@code cognito:groups} (array JSON),
     * usar {@link #extractSub} directamente o leerlo desde el
     * {@link org.springframework.security.oauth2.jwt.Jwt} de Spring Security.</p>
     *
     * @param accessToken JWT en formato {@code header.payload.signature}.
     * @return mapa con claims de tipo string del payload.
     * @throws IllegalStateException si el token no tiene exactamente 3 partes.
     */
    public static Map<String, Object> extractClaims(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("JWT malformado: se esperaban 3 partes separadas por '.'");
        }

        // El padding en Base64 URL es opcional en JWT — añadimos el padding que le falte
        String paddedPayload = padBase64(parts[1]);
        byte[] decoded = Base64.getUrlDecoder().decode(paddedPayload);
        String json = new String(decoded, StandardCharsets.UTF_8);

        Map<String, Object> claims = new HashMap<>();
        Matcher m = STRING_CLAIM.matcher(json);
        while (m.find()) {
            claims.put(m.group(1), m.group(2));
        }
        return claims;
    }

    private static String padBase64(String s) {
        return switch (s.length() % 4) {
            case 2  -> s + "==";
            case 3  -> s + "=";
            default -> s;
        };
    }
}
