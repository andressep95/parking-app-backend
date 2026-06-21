package com.cloudcentinel.parkingapp.parking;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ParkingSessionRepository {

    private final JdbcClient jdbc;

    public ParkingSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SessionRow> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, location_id, plate, vehicle_type::text, status::text,
                       entry_at, exit_at, duration_minutes, calculated_amount,
                       operator_id_in, terminal_id_in, shift_id
                FROM parking_sessions WHERE id = :id
                """)
                .param("id", id)
                .query(ParkingSessionRepository::mapRow)
                .optional();
    }

    public boolean existsById(UUID id) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM parking_sessions WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public boolean hasActivePlateInLocation(String plate, UUID locationId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM parking_sessions
                WHERE plate = :plate AND location_id = :locationId AND status = 'ACTIVE'
                """)
                .param("plate",      plate)
                .param("locationId", locationId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public void insert(UUID id, UUID locationId, String plate, String vehicleType,
                       Instant entryAt, UUID operatorId, UUID terminalId,
                       UUID shiftId, String tariffSnapshotJson) {
        jdbc.sql("""
                INSERT INTO parking_sessions
                    (id, location_id, plate, vehicle_type, status, entry_at,
                     operator_id_in, terminal_id_in, shift_id, tariff_snapshot)
                VALUES
                    (:id, :locationId, :plate, :vehicleType::vehicle_type, 'ACTIVE',
                     :entryAt, :operatorId, :terminalId, :shiftId,
                     CAST(:snapshot AS jsonb))
                """)
                .param("id",          id)
                .param("locationId",  locationId)
                .param("plate",       plate)
                .param("vehicleType", vehicleType)
                .param("entryAt",     Timestamp.from(entryAt))
                .param("operatorId",  operatorId)
                .param("terminalId",  terminalId)
                .param("shiftId",     shiftId)
                .param("snapshot",    tariffSnapshotJson)
                .update();
    }

    public void complete(UUID id, Instant exitAt, int durationMinutes, BigDecimal calculatedAmount) {
        jdbc.sql("""
                UPDATE parking_sessions SET
                    status            = 'COMPLETED',
                    exit_at           = :exitAt,
                    duration_minutes  = :durationMinutes,
                    calculated_amount = :calculatedAmount
                WHERE id = :id
                """)
                .param("exitAt",           Timestamp.from(exitAt))
                .param("durationMinutes",  durationMinutes)
                .param("calculatedAmount", calculatedAmount)
                .param("id",               id)
                .update();
    }

    public record SessionRow(
            UUID       id,
            UUID       locationId,
            String     plate,
            String     vehicleType,
            String     status,
            Instant    entryAt,
            Instant    exitAt,
            Integer    durationMinutes,
            BigDecimal calculatedAmount,
            UUID       operatorId,
            UUID       terminalId,
            UUID       shiftId
    ) {}

    private static SessionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp exitAt = rs.getTimestamp("exit_at");
        return new SessionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("location_id")),
                rs.getString("plate"),
                rs.getString("vehicle_type"),
                rs.getString("status"),
                rs.getTimestamp("entry_at").toInstant(),
                exitAt != null ? exitAt.toInstant() : null,
                rs.getObject("duration_minutes", Integer.class),
                rs.getBigDecimal("calculated_amount"),
                UUID.fromString(rs.getString("operator_id_in")),
                UUID.fromString(rs.getString("terminal_id_in")),
                UUID.fromString(rs.getString("shift_id"))
        );
    }
}
