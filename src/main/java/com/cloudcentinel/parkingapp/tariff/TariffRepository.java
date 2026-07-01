package com.cloudcentinel.parkingapp.tariff;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class TariffRepository {

    private final JdbcClient jdbc;

    public TariffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tariff> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, location_id, vehicle_type::text, tariff_type::text, name,
                       price_per_minute, grace_minutes, max_charge, flat_amount,
                       is_active, valid_from, valid_until
                FROM tariffs WHERE id = :id
                """)
                .param("id", id)
                .query(TariffRepository::mapRow)
                .optional()
                .map(this::populateBrackets);
    }

    public List<Tariff> findByLocationId(UUID locationId, Boolean activeOnly) {
        String sql = Boolean.FALSE.equals(activeOnly)
                ? """
                  SELECT id, location_id, vehicle_type::text, tariff_type::text, name,
                         price_per_minute, grace_minutes, max_charge, flat_amount,
                         is_active, valid_from, valid_until
                  FROM tariffs WHERE location_id = :locationId
                  ORDER BY valid_from DESC
                  """
                : """
                  SELECT id, location_id, vehicle_type::text, tariff_type::text, name,
                         price_per_minute, grace_minutes, max_charge, flat_amount,
                         is_active, valid_from, valid_until
                  FROM tariffs WHERE location_id = :locationId AND is_active = true
                  ORDER BY vehicle_type
                  """;

        List<Tariff> rows = jdbc.sql(sql)
                .param("locationId", locationId)
                .query(TariffRepository::mapRow)
                .list();

        return populateBracketsForList(rows);
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

    public Tariff insert(UUID locationId, CreateTariffRequest req) {
        UUID id = UUID.randomUUID();
        int graceMinutes = req.graceMinutes() != null ? req.graceMinutes() : 0;

        jdbc.sql("""
                INSERT INTO tariffs
                    (id, location_id, vehicle_type, tariff_type, name,
                     price_per_minute, grace_minutes, max_charge, flat_amount,
                     is_active, valid_from)
                VALUES
                    (:id, :locationId, :vehicleType::vehicle_type, :tariffType::tariff_type, :name,
                     :pricePerMinute, :graceMinutes, :maxCharge, :flatAmount,
                     true, now())
                """)
                .param("id",             id)
                .param("locationId",     locationId)
                .param("vehicleType",    req.vehicleType())
                .param("tariffType",     req.tariffType())
                .param("name",           req.name())
                .param("pricePerMinute", req.pricePerMinute())
                .param("graceMinutes",   graceMinutes)
                .param("maxCharge",      req.maxCharge())
                .param("flatAmount",     req.flatAmount())
                .update();

        if ("BRACKET".equals(req.tariffType()) && req.brackets() != null) {
            insertBrackets(id, req.brackets());
        }

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

    private void insertBrackets(UUID tariffId, List<CreateTariffRequest.BracketRequest> brackets) {
        for (CreateTariffRequest.BracketRequest br : brackets) {
            jdbc.sql("""
                    INSERT INTO tariff_brackets
                        (id, tariff_id, position, from_minute, to_minute, price_per_minute)
                    VALUES
                        (:id, :tariffId, :position, :fromMinute, :toMinute, :pricePerMinute)
                    """)
                    .param("id",             UUID.randomUUID())
                    .param("tariffId",       tariffId)
                    .param("position",       br.position())
                    .param("fromMinute",     br.fromMinute())
                    .param("toMinute",       br.toMinute())
                    .param("pricePerMinute", br.pricePerMinute())
                    .update();
        }
    }

    private List<TariffBracket> findBracketsForTariff(UUID tariffId) {
        return jdbc.sql("""
                SELECT id, tariff_id, position, from_minute, to_minute, price_per_minute
                FROM tariff_brackets WHERE tariff_id = :tariffId ORDER BY position
                """)
                .param("tariffId", tariffId)
                .query(TariffRepository::mapBracketRow)
                .list();
    }

    private Tariff populateBrackets(Tariff t) {
        if (!"BRACKET".equals(t.tariffType())) return t;
        List<TariffBracket> brackets = findBracketsForTariff(t.id());
        return new Tariff(t.id(), t.locationId(), t.vehicleType(), t.tariffType(), t.name(),
                t.pricePerMinute(), t.graceMinutes(), t.maxCharge(), t.flatAmount(),
                brackets, t.isActive(), t.validFrom(), t.validUntil());
    }

    private List<Tariff> populateBracketsForList(List<Tariff> tariffs) {
        return tariffs.stream().map(this::populateBrackets).toList();
    }

    static Tariff mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp validUntil = rs.getTimestamp("valid_until");
        return new Tariff(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("location_id")),
                rs.getString("vehicle_type"),
                rs.getString("tariff_type"),
                rs.getString("name"),
                rs.getBigDecimal("price_per_minute"),
                rs.getInt("grace_minutes"),
                rs.getBigDecimal("max_charge"),
                rs.getBigDecimal("flat_amount"),
                null,
                rs.getBoolean("is_active"),
                rs.getTimestamp("valid_from").toInstant(),
                validUntil != null ? validUntil.toInstant() : null
        );
    }

    private static TariffBracket mapBracketRow(ResultSet rs, int rowNum) throws SQLException {
        return new TariffBracket(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tariff_id")),
                rs.getInt("position"),
                rs.getInt("from_minute"),
                rs.getObject("to_minute", Integer.class),
                rs.getBigDecimal("price_per_minute")
        );
    }
}
