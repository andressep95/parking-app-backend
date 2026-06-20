package com.cloudcentinel.parkingapp.session;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a la tabla {@code user_sessions} usando {@link JdbcClient}.
 *
 * <h2>Invariante de sesión única</h2>
 * <p>La columna {@code user_id} tiene restricción {@code UNIQUE}, por lo que
 * la operación central es un {@code UPSERT} ({@code INSERT ... ON CONFLICT DO UPDATE}).
 * Esto reemplaza cualquier sesión previa sin necesidad de un {@code DELETE} explícito
 * previo, manteniendo la atomicidad en una sola operación.</p>
 *
 * <h2>Limpieza de sesiones expiradas</h2>
 * <p>Las sesiones expiradas no se limpian con un job periódico. En cambio, se
 * eliminan de forma lazy dentro del mismo flujo de login
 * ({@link #deleteExpired()}), antes del {@code UPSERT}. Esto es suficiente a
 * la escala del sistema y elimina la necesidad de un scheduler externo.</p>
 *
 * <h2>Tipo INET de PostgreSQL</h2>
 * <p>La columna {@code ip_address} es tipo {@code INET} en PostgreSQL. El driver
 * JDBC la lee como {@code String} vía {@code rs.getString()}. Para escribir,
 * se usa {@code CAST(:ip AS inet)} en el SQL. Si la IP es {@code null}
 * (sesión sin terminal, ej: uso interno), se pasa {@code null} directamente.</p>
 */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta o reemplaza la sesión activa del usuario.
     *
     * <p>Usa {@code ON CONFLICT (user_id) DO UPDATE} para garantizar que siempre
     * haya a lo sumo una sesión por usuario. Si ya existía una sesión para
     * {@code userId}, se sobreescribe con los nuevos valores (nuevo terminal,
     * nueva IP, nuevo TTL de 24 horas). El {@code id} de la sesión también se
     * regenera para que los logs de auditoría puedan distinguir sesiones distintas
     * aunque pertenezcan al mismo usuario.</p>
     *
     * @param userId     ID interno del usuario (FK a {@code users.id}).
     * @param terminalId ID del terminal POS desde el que se autenticó.
     *                   {@code null} para sesiones web (ADMIN / CUSTOMER).
     * @param ipAddress  IP del cliente. {@code null} si no está disponible.
     */
    public void upsert(UUID userId, UUID terminalId, String ipAddress) {
        jdbc.sql("""
                INSERT INTO user_sessions (id, user_id, terminal_id, ip_address, expires_at)
                VALUES (gen_random_uuid(), :userId, :terminalId,
                        CAST(:ipAddress AS inet),
                        now() + INTERVAL '24 hours')
                ON CONFLICT (user_id) DO UPDATE SET
                    id          = gen_random_uuid(),
                    terminal_id = EXCLUDED.terminal_id,
                    ip_address  = EXCLUDED.ip_address,
                    started_at  = now(),
                    expires_at  = now() + INTERVAL '24 hours'
                """)
                .param("userId",     userId)
                .param("terminalId", terminalId)
                .param("ipAddress",  ipAddress)
                .update();
    }

    /**
     * Elimina la sesión activa de un usuario.
     *
     * <p>Se llama en dos escenarios: logout voluntario del propio usuario y
     * cierre remoto de sesión iniciado por un ADMIN. No lanza excepción si
     * el usuario no tenía sesión activa ({@code DELETE} sin filas afectadas
     * es una operación válida).</p>
     *
     * @param userId ID interno del usuario cuya sesión se elimina.
     */
    public void deleteByUserId(UUID userId) {
        jdbc.sql("DELETE FROM user_sessions WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /**
     * Busca la sesión activa de un usuario.
     *
     * <p>Retorna {@link Optional#empty()} si el usuario no tiene sesión o
     * si la sesión encontrada ya expiró (aunque {@link #deleteExpired()} no
     * la haya limpiado todavía). En ese segundo caso la sesión se considera
     * inexistente a efectos de negocio.</p>
     *
     * @param userId ID interno del usuario.
     * @return sesión activa si existe y no ha expirado.
     */
    public Optional<UserSession> findByUserId(UUID userId) {
        return jdbc.sql("""
                SELECT id, user_id, terminal_id, ip_address::text,
                       started_at, expires_at
                FROM user_sessions
                WHERE user_id = :userId
                  AND expires_at > now()
                """)
                .param("userId", userId)
                .query(SessionRepository::mapRow)
                .optional();
    }

    /**
     * Elimina todas las sesiones cuyo {@code expires_at} ya pasó.
     *
     * <p>Llamado al inicio de cada flujo de login para mantener la tabla
     * limpia de forma lazy, sin necesidad de un scheduler. A la escala de
     * este sistema (cientos de terminales, no millones) el impacto en
     * rendimiento es despreciable.</p>
     */
    public void deleteExpired() {
        jdbc.sql("DELETE FROM user_sessions WHERE expires_at < now()")
                .update();
    }

    private static UserSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        String terminalIdStr = rs.getString("terminal_id");
        return new UserSession(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                terminalIdStr != null ? UUID.fromString(terminalIdStr) : null,
                rs.getString("ip_address"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()
        );
    }
}
