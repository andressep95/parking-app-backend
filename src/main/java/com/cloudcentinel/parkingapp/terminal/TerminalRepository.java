package com.cloudcentinel.parkingapp.terminal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TerminalRepository {

    private final JdbcClient jdbc;

    public TerminalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Terminal> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals WHERE id = :id
                """)
                .param("id", id)
                .query(TerminalRepository::mapRow)
                .optional();
    }

    public Optional<Terminal> findBySerialNumber(String serialNumber) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals WHERE serial_number = :serialNumber
                """)
                .param("serialNumber", serialNumber)
                .query(TerminalRepository::mapRow)
                .optional();
    }

    public List<Terminal> findAll() {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals ORDER BY org_id, location_id
                """)
                .query(TerminalRepository::mapRow)
                .list();
    }

    public List<Terminal> findByOrgId(UUID orgId) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals WHERE org_id = :orgId ORDER BY location_id
                """)
                .param("orgId", orgId)
                .query(TerminalRepository::mapRow)
                .list();
    }

    public List<Terminal> findByLocationId(UUID locationId) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id, location_id,
                       status::text, active_operator_id, app_version, last_heartbeat
                FROM terminals WHERE location_id = :locationId
                """)
                .param("locationId", locationId)
                .query(TerminalRepository::mapRow)
                .list();
    }

    public boolean existsBySerialNumber(String serialNumber) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM terminals WHERE serial_number = :sn")
                .param("sn", serialNumber)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public boolean hasActiveParkingSessions(UUID terminalId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM parking_sessions
                WHERE terminal_id_in = :id AND status = 'ACTIVE'
                """)
                .param("id", terminalId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public Terminal insert(String serialNumber, String model, UUID orgId,
                           UUID locationId, String appVersion) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO terminals
                    (id, serial_number, model, org_id, location_id, status, app_version)
                VALUES
                    (:id, :sn, :model, :orgId, :locationId, 'OFFLINE', :appVersion)
                """)
                .param("id",         id)
                .param("sn",         serialNumber)
                .param("model",      model)
                .param("orgId",      orgId)
                .param("locationId", locationId)
                .param("appVersion", appVersion)
                .update();
        return findById(id).orElseThrow();
    }

    public void update(UUID id, String model, UUID locationId, String appVersion) {
        jdbc.sql("""
                UPDATE terminals SET
                    model       = COALESCE(:model,      model),
                    location_id = COALESCE(:locationId, location_id),
                    app_version = COALESCE(:appVersion, app_version)
                WHERE id = :id
                """)
                .param("model",      model)
                .param("locationId", locationId)
                .param("appVersion", appVersion)
                .param("id",         id)
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM terminals WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE terminals SET status = :s::terminal_status WHERE id = :id")
                .param("s",  status)
                .param("id", id)
                .update();
    }

    public void setActiveOperator(UUID id, UUID operatorId) {
        jdbc.sql("UPDATE terminals SET active_operator_id = :operatorId WHERE id = :id")
                .param("operatorId", operatorId)
                .param("id",         id)
                .update();
    }

    static Terminal mapRow(ResultSet rs, int rowNum) throws SQLException {
        String activeOpStr      = rs.getString("active_operator_id");
        Timestamp lastHeartbeat = rs.getTimestamp("last_heartbeat");
        return new Terminal(
                UUID.fromString(rs.getString("id")),
                rs.getString("serial_number"),
                rs.getString("model"),
                UUID.fromString(rs.getString("org_id")),
                UUID.fromString(rs.getString("location_id")),
                rs.getString("status"),
                activeOpStr != null ? UUID.fromString(activeOpStr) : null,
                rs.getString("app_version"),
                lastHeartbeat != null ? lastHeartbeat.toInstant() : null
        );
    }
}
