package com.cloudcentinel.parkingapp.shift;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShiftRepository {

    private final JdbcClient jdbc;

    public ShiftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Shift> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, operator_id, terminal_id, location_id,
                       status::text, started_at, closed_at, opening_cash, closing_cash
                FROM shifts WHERE id = :id
                """)
                .param("id", id)
                .query(ShiftRepository::mapRow)
                .optional();
    }

    public Optional<Shift> findActiveByOperator(UUID operatorId) {
        return jdbc.sql("""
                SELECT id, operator_id, terminal_id, location_id,
                       status::text, started_at, closed_at, opening_cash, closing_cash
                FROM shifts WHERE operator_id = :operatorId AND status = 'ACTIVE'
                """)
                .param("operatorId", operatorId)
                .query(ShiftRepository::mapRow)
                .optional();
    }

    public List<Shift> findByFilters(UUID operatorId, UUID locationId, String date, String status) {
        return jdbc.sql("""
                SELECT id, operator_id, terminal_id, location_id,
                       status::text, started_at, closed_at, opening_cash, closing_cash
                FROM shifts
                WHERE (:operatorId IS NULL OR operator_id = :operatorId)
                  AND (:locationId IS NULL OR location_id = :locationId)
                  AND (:status     IS NULL OR status::text = :status)
                  AND (:date       IS NULL OR started_at::date = :date::date)
                ORDER BY started_at DESC
                LIMIT 100
                """)
                .param("operatorId", operatorId)
                .param("locationId", locationId)
                .param("status",     status)
                .param("date",       date)
                .query(ShiftRepository::mapRow)
                .list();
    }

    public boolean hasActiveParkingSessions(UUID shiftId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM parking_sessions
                WHERE shift_id = :shiftId AND status = 'ACTIVE'
                """)
                .param("shiftId", shiftId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public Shift insert(UUID operatorId, UUID terminalId, UUID locationId, BigDecimal openingCash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO shifts (id, operator_id, terminal_id, location_id, status, opening_cash)
                VALUES (:id, :operatorId, :terminalId, :locationId, 'ACTIVE', :openingCash)
                """)
                .param("id",          id)
                .param("operatorId",  operatorId)
                .param("terminalId",  terminalId)
                .param("locationId",  locationId)
                .param("openingCash", openingCash)
                .update();
        return findById(id).orElseThrow();
    }

    public Shift close(UUID id, BigDecimal closingCash) {
        jdbc.sql("""
                UPDATE shifts SET
                    status       = 'CLOSED',
                    closed_at    = now(),
                    closing_cash = :closingCash
                WHERE id = :id
                """)
                .param("closingCash", closingCash)
                .param("id",          id)
                .update();
        return findById(id).orElseThrow();
    }

    static Shift mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Shift(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("operator_id")),
                UUID.fromString(rs.getString("terminal_id")),
                UUID.fromString(rs.getString("location_id")),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                closedAt != null ? closedAt.toInstant() : null,
                rs.getBigDecimal("opening_cash"),
                rs.getBigDecimal("closing_cash")
        );
    }
}
