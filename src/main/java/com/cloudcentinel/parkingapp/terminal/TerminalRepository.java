package com.cloudcentinel.parkingapp.terminal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Acceso a la tabla {@code terminals} usando {@link JdbcClient}.
 *
 * <p><b>Stub de compilación</b>: contiene solo el método que {@code AuthService}
 * necesita ahora. El resto del CRUD (registrar, listar por sede, cambiar estado,
 * heartbeat) se añade cuando se construya la capa completa de terminal.</p>
 */
@Repository
public class TerminalRepository {

    private final JdbcClient jdbc;

    public TerminalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca un terminal por número de serie.
     *
     * <p>El número de serie es la clave de búsqueda en el flujo de login del POS.
     * El índice {@code UNIQUE} sobre {@code serial_number} garantiza O(1) para
     * esta consulta, que es el hot path de autenticación de operadores.</p>
     *
     * @param serialNumber número de serie físico del dispositivo TUU.
     * @return el terminal si está registrado, {@link Optional#empty()} en caso contrario.
     */
    public Optional<Terminal> findBySerialNumber(String serialNumber) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals
                WHERE serial_number = :serialNumber
                """)
                .param("serialNumber", serialNumber)
                .query(TerminalRepository::mapRow)
                .optional();
    }

    static Terminal mapRow(ResultSet rs, int rowNum) throws SQLException {
        String activeOperatorStr = rs.getString("active_operator_id");
        Timestamp lastHeartbeat  = rs.getTimestamp("last_heartbeat");
        return new Terminal(
                java.util.UUID.fromString(rs.getString("id")),
                rs.getString("serial_number"),
                rs.getString("model"),
                java.util.UUID.fromString(rs.getString("org_id")),
                java.util.UUID.fromString(rs.getString("location_id")),
                rs.getString("status"),
                activeOperatorStr != null ? java.util.UUID.fromString(activeOperatorStr) : null,
                rs.getString("app_version"),
                lastHeartbeat != null ? lastHeartbeat.toInstant() : null
        );
    }
}
