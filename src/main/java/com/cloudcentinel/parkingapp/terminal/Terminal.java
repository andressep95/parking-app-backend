package com.cloudcentinel.parkingapp.terminal;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una fila de la tabla {@code terminals}.
 *
 * <p>El terminal es el punto físico de cobro: una máquina TUU Haulmer con un
 * número de serie único. El {@code serialNumber} es la clave de búsqueda en el
 * flujo de login del POS ({@code POST /auth/device}): el dispositivo se identifica
 * con su propio número de serie para que el backend pueda validar que está
 * registrado y activo antes de emitir tokens.</p>
 *
 * @param id               UUID interno. PK de la tabla.
 * @param serialNumber     Número de serie físico del dispositivo TUU. {@code UNIQUE}
 *                         en la tabla — el hot path de autenticación busca por este campo.
 * @param model            Modelo del dispositivo (ej: {@code "TUU S1"}).
 * @param orgId            FK a {@code organizations.id}. Organización propietaria del terminal.
 * @param locationId       FK a {@code locations.id}. Sede donde está instalado.
 * @param status           Estado del terminal: {@code ONLINE}, {@code OFFLINE} o
 *                         {@code MAINTENANCE}. Solo {@code ONLINE} permite login.
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
        UUID locationId,
        String status,
        UUID activeOperatorId,
        String appVersion,
        Instant lastHeartbeat
) {}
