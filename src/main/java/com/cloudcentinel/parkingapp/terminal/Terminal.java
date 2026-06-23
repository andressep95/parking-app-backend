package com.cloudcentinel.parkingapp.terminal;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una fila de la tabla {@code terminals}.
 *
 * <p>El terminal es el dispositivo físico POS (TUU) registrado en una organización.
 * Su único propósito de negocio es prevenir que un OPERATOR inicie sesión desde dos
 * terminales distintos al mismo tiempo. La locación de operación del OPERATOR proviene
 * de {@code users.location_id}, no del terminal.</p>
 *
 * @param id               UUID interno. PK de la tabla.
 * @param serialNumber     Número de serie físico del dispositivo TUU. {@code UNIQUE}
 *                         en la tabla — el hot path de autenticación busca por este campo.
 * @param model            Modelo del dispositivo (ej: {@code "TUU S1"}).
 * @param orgId            FK a {@code organizations.id}. Organización propietaria del terminal.
 * @param status           Estado del terminal: {@code ONLINE}, {@code OFFLINE} o
 *                         {@code MAINTENANCE}.
 * @param activeOperatorId FK a {@code users.id}. Operador actualmente logueado en
 *                         el terminal. {@code null} si no hay sesión activa.
 * @param appVersion       Versión de la app Android instalada en el dispositivo (opcional).
 * @param lastHeartbeat    Último timestamp de heartbeat recibido del dispositivo (opcional).
 */
public record Terminal(
        UUID id,
        String serialNumber,
        String model,
        UUID orgId,
        String status,
        UUID activeOperatorId,
        String appVersion,
        Instant lastHeartbeat
) {}
