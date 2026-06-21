package com.cloudcentinel.parkingapp.tariff;

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
public class TariffRepository {

    private final JdbcClient jdbc;

    public TariffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tariff> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, location_id, vehicle_type::text, name,
                       price_per_hour, minimum_charge, grace_minutes,
                       is_active, valid_from, valid_until
                FROM tariffs WHERE id = :id
                """)
                .param("id", id)
                .query(TariffRepository::mapRow)
                .optional();
    }

    public List<Tariff> findByLocationId(UUID locationId, Boolean activeOnly) {
        if (Boolean.FALSE.equals(activeOnly)) {
            return jdbc.sql("""
                    SELECT id, location_id, vehicle_type::text, name,
                           price_per_hour, minimum_charge, grace_minutes,
                           is_active, valid_from, valid_until
                    FROM tariffs WHERE location_id = :locationId
                    ORDER BY valid_from DESC
                    """)
                    .param("locationId", locationId)
                    .query(TariffRepository::mapRow)
                    .list();
        }
        return jdbc.sql("""
                SELECT id, location_id, vehicle_type::text, name,
                       price_per_hour, minimum_charge, grace_minutes,
                       is_active, valid_from, valid_until
                FROM tariffs WHERE location_id = :locationId AND is_active = true
                ORDER BY vehicle_type
                """)
                .param("locationId", locationId)
                .query(TariffRepository::mapRow)
                .list();
    }

    public List<Tariff> findActiveTariffsForLocation(UUID locationId) {
        return findByLocationId(locationId, true);
    }

    public void deactivatePrevious(UUID locationId, String vehicleType) {
        jdbc.sql("""
                UPDATE tariffs SET is_active = false, valid_until = now()
                WHERE location_id = :locationId
                  AND vehicle_type = :vehicleType::vehicle_type
                  AND is_active = true
                """)
                .param("locationId",  locationId)
                .param("vehicleType", vehicleType)
                .update();
    }

    public Tariff insert(UUID locationId, String vehicleType, String name,
                         BigDecimal pricePerHour, BigDecimal minimumCharge, int graceMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tariffs
                    (id, location_id, vehicle_type, name,
                     price_per_hour, minimum_charge, grace_minutes, is_active, valid_from)
                VALUES
                    (:id, :locationId, :vehicleType::vehicle_type, :name,
                     :pricePerHour, :minimumCharge, :graceMinutes, true, now())
                """)
                .param("id",            id)
                .param("locationId",    locationId)
                .param("vehicleType",   vehicleType)
                .param("name",          name)
                .param("pricePerHour",  pricePerHour)
                .param("minimumCharge", minimumCharge)
                .param("graceMinutes",  graceMinutes)
                .update();
        return findById(id).orElseThrow();
    }

    public void deactivate(UUID id) {
        jdbc.sql("""
                UPDATE tariffs SET is_active = false, valid_until = now()
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    static Tariff mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp validUntil = rs.getTimestamp("valid_until");
        return new Tariff(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("location_id")),
                rs.getString("vehicle_type"),
                rs.getString("name"),
                rs.getBigDecimal("price_per_hour"),
                rs.getBigDecimal("minimum_charge"),
                rs.getInt("grace_minutes"),
                rs.getBoolean("is_active"),
                rs.getTimestamp("valid_from").toInstant(),
                validUntil != null ? validUntil.toInstant() : null
        );
    }
}
