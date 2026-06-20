package com.cloudcentinel.parkingapp.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una sesión activa en la tabla {@code user_sessions}.
 *
 * <p>La restricción {@code UNIQUE (user_id)} en la tabla garantiza que cada
 * usuario tiene a lo sumo una sesión activa simultánea. Cuando el operador
 * hace login desde un terminal, el {@code UPSERT} en {@link SessionRepository}
 * reemplaza automáticamente la sesión anterior sin necesidad de deletearla
 * primero.</p>
 *
 * <p>El campo {@code expiresAt} reemplaza el TTL de DynamoDB. La limpieza se
 * realiza de forma lazy en cada login ({@link SessionRepository#deleteExpired()})
 * en lugar de un job periódico, lo que es suficiente a esta escala.</p>
 *
 * @param id         UUID generado por PostgreSQL ({@code gen_random_uuid()}).
 * @param userId     FK a {@code users.id}. Columna con {@code UNIQUE} — garantiza sesión única.
 * @param terminalId FK a {@code terminals.id}. Nulo para sesiones web (ADMIN, CUSTOMER).
 * @param ipAddress  IP del cliente en el momento del login. Mapeada desde tipo {@code INET}
 *                   de PostgreSQL; se almacena como {@code String} en Java.
 * @param startedAt  Timestamp de inicio de la sesión.
 * @param expiresAt  Timestamp de expiración. Las sesiones de OPERATOR duran 24 horas.
 */
public record UserSession(
        UUID id,
        UUID userId,
        UUID terminalId,
        String ipAddress,
        Instant startedAt,
        Instant expiresAt
) {}
