package com.cloudcentinel.parkingapp.transaction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
