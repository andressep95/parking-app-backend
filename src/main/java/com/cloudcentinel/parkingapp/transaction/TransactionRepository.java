package com.cloudcentinel.parkingapp.transaction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TransactionResponse> listByLocation(UUID locationId) {
        return jdbc.sql("""
                SELECT ps.id AS parking_session_id, t.id AS transaction_id, ps.plate,
                       ps.vehicle_type::text, ps.status::text, ps.entry_at, ps.exit_at,
                       ps.duration_minutes, t.amount, t.payment_method::text,
                       t.tuu_reference, t.transaction_at
                FROM parking_sessions ps
                LEFT JOIN transactions t ON t.parking_session_id = ps.id
                WHERE ps.location_id = :locationId
                ORDER BY COALESCE(t.transaction_at, ps.entry_at) DESC
                """)
                .param("locationId", locationId)
                .query(TransactionRepository::mapRow)
                .list();
    }

    private static TransactionResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp exitAt = rs.getTimestamp("exit_at");
        Timestamp transactionAt = rs.getTimestamp("transaction_at");
        String transactionId = rs.getString("transaction_id");
        return new TransactionResponse(
                UUID.fromString(rs.getString("parking_session_id")),
                transactionId != null ? UUID.fromString(transactionId) : null,
                rs.getString("plate"),
                rs.getString("vehicle_type"),
                rs.getString("status"),
                rs.getTimestamp("entry_at").toInstant(),
                exitAt != null ? exitAt.toInstant() : null,
                rs.getObject("duration_minutes", Integer.class),
                rs.getBigDecimal("amount"),
                rs.getString("payment_method"),
                rs.getString("tuu_reference"),
                transactionAt != null ? transactionAt.toInstant() : null
        );
    }

    public boolean existsById(UUID id) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM transactions WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public void insert(UUID id, UUID parkingSessionId, UUID shiftId,
                       BigDecimal amount, String paymentMethod, String tuuReference) {
        jdbc.sql("""
                INSERT INTO transactions
                    (id, parking_session_id, shift_id, amount, payment_method, tuu_reference)
                VALUES
                    (:id, :parkingSessionId, :shiftId, :amount,
                     :paymentMethod::payment_method, :tuuReference)
                """)
                .param("id",               id)
                .param("parkingSessionId", parkingSessionId)
                .param("shiftId",          shiftId)
                .param("amount",           amount)
                .param("paymentMethod",    paymentMethod)
                .param("tuuReference",     tuuReference)
                .update();
    }
}
