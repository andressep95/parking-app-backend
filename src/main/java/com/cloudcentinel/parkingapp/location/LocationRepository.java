package com.cloudcentinel.parkingapp.location;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LocationRepository {

    private final JdbcClient jdbc;

    public LocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Location> findById(UUID id) {
        return jdbc.sql("""
                SELECT l.id, l.org_id, l.location_name, l.address, l.city, l.capacity,
                       l.max_operators, l.timezone, l.location_status::text, l.created_at,
                       (SELECT COUNT(*) FROM users u
                        WHERE u.location_id = l.id
                          AND u.role = 'OPERATOR'
                          AND u.user_status = 'ACTIVE') AS active_operators_count
                FROM locations l WHERE l.id = :id
                """)
                .param("id", id)
                .query(LocationRepository::mapRow)
                .optional();
    }

    public List<Location> findAll() {
        return jdbc.sql("""
                SELECT l.id, l.org_id, l.location_name, l.address, l.city, l.capacity,
                       l.max_operators, l.timezone, l.location_status::text, l.created_at,
                       (SELECT COUNT(*) FROM users u
                        WHERE u.location_id = l.id
                          AND u.role = 'OPERATOR'
                          AND u.user_status = 'ACTIVE') AS active_operators_count
                FROM locations l ORDER BY l.created_at DESC
                """)
                .query(LocationRepository::mapRow)
                .list();
    }

    public List<Location> findByOrgId(UUID orgId) {
        return jdbc.sql("""
                SELECT l.id, l.org_id, l.location_name, l.address, l.city, l.capacity,
                       l.max_operators, l.timezone, l.location_status::text, l.created_at,
                       (SELECT COUNT(*) FROM users u
                        WHERE u.location_id = l.id
                          AND u.role = 'OPERATOR'
                          AND u.user_status = 'ACTIVE') AS active_operators_count
                FROM locations l WHERE l.org_id = :orgId ORDER BY l.created_at DESC
                """)
                .param("orgId", orgId)
                .query(LocationRepository::mapRow)
                .list();
    }

    public boolean hasAssignedOperators(UUID locationId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM users WHERE location_id = :id")
                .param("id", locationId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public boolean hasActiveParkingSessions(UUID locationId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM parking_sessions
                WHERE location_id = :id AND status = 'ACTIVE'
                """)
                .param("id", locationId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public Location insert(UUID orgId, String locationName, String address,
                           String city, int capacity, int maxOperators, String timezone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO locations
                    (id, org_id, location_name, address, city, capacity, max_operators, timezone, location_status)
                VALUES
                    (:id, :orgId, :locationName, :address, :city, :capacity, :maxOperators, :timezone, 'ACTIVE')
                """)
                .param("id",           id)
                .param("orgId",        orgId)
                .param("locationName", locationName)
                .param("address",      address)
                .param("city",         city)
                .param("capacity",     capacity)
                .param("maxOperators", maxOperators)
                .param("timezone",     timezone)
                .update();
        return findById(id).orElseThrow();
    }

    public void update(UUID id, String locationName, String address,
                       String city, Integer capacity, Integer maxOperators, String timezone) {
        jdbc.sql("""
                UPDATE locations SET
                    location_name = COALESCE(:locationName, location_name),
                    address       = COALESCE(:address,      address),
                    city          = COALESCE(:city,         city),
                    capacity      = COALESCE(:capacity,     capacity),
                    max_operators = COALESCE(:maxOperators, max_operators),
                    timezone      = COALESCE(:timezone,     timezone)
                WHERE id = :id
                """)
                .param("locationName", locationName)
                .param("address",      address)
                .param("city",         city)
                .param("capacity",     capacity)
                .param("maxOperators", maxOperators)
                .param("timezone",     timezone)
                .param("id",           id)
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM locations WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE locations SET location_status = :s::location_status WHERE id = :id")
                .param("s",  status)
                .param("id", id)
                .update();
    }

    static Location mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Location(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("org_id")),
                rs.getString("location_name"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getInt("capacity"),
                rs.getInt("max_operators"),
                rs.getInt("active_operators_count"),
                rs.getString("timezone"),
                rs.getString("location_status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
