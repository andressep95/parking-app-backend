package com.cloudcentinel.parkingapp.vehicle;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleRepository {

    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String plate, String vehicleType) {
        jdbc.sql("""
                INSERT INTO vehicles (plate, vehicle_type, first_seen_at, last_seen_at, total_sessions)
                VALUES (:plate, :vehicleType::vehicle_type, now(), now(), 1)
                ON CONFLICT (plate) DO UPDATE SET
                    last_seen_at   = now(),
                    total_sessions = vehicles.total_sessions + 1
                """)
                .param("plate",       plate)
                .param("vehicleType", vehicleType)
                .update();
    }
}
