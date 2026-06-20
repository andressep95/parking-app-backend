package com.cloudcentinel.parkingapp.organization;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrganizationRepository {

    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Organization> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, org_name, rut_company, org_email, phone_number,
                       org_status::text, admin_user_id, created_at
                FROM organizations WHERE id = :id
                """)
                .param("id", id)
                .query(OrganizationRepository::mapRow)
                .optional();
    }

    public List<Organization> findAll() {
        return jdbc.sql("""
                SELECT id, org_name, rut_company, org_email, phone_number,
                       org_status::text, admin_user_id, created_at
                FROM organizations ORDER BY created_at DESC
                """)
                .query(OrganizationRepository::mapRow)
                .list();
    }

    public boolean existsByRutCompany(String rutCompany) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM organizations WHERE rut_company = :rut")
                .param("rut", rutCompany)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public boolean hasOnlineTerminals(UUID orgId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM terminals
                WHERE org_id = :orgId AND status = 'ONLINE'
                """)
                .param("orgId", orgId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public Organization insert(String orgName, String rutCompany, String orgEmail,
                               String phoneNumber, UUID adminUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO organizations (id, org_name, rut_company, org_email,
                                           phone_number, org_status, admin_user_id)
                VALUES (:id, :orgName, :rutCompany, :orgEmail, :phone, 'ACTIVE', :adminUserId)
                """)
                .param("id", id)
                .param("orgName", orgName)
                .param("rutCompany", rutCompany)
                .param("orgEmail", orgEmail)
                .param("phone", phoneNumber)
                .param("adminUserId", adminUserId)
                .update();
        return findById(id).orElseThrow();
    }

    public void update(UUID id, String orgName, String rutCompany, String orgEmail, String phoneNumber) {
        jdbc.sql("""
                UPDATE organizations SET
                    org_name     = COALESCE(:orgName,     org_name),
                    rut_company  = COALESCE(:rutCompany,  rut_company),
                    org_email    = COALESCE(:orgEmail,    org_email),
                    phone_number = COALESCE(:phone,       phone_number)
                WHERE id = :id
                """)
                .param("orgName",    orgName)
                .param("rutCompany", rutCompany)
                .param("orgEmail",   orgEmail)
                .param("phone",      phoneNumber)
                .param("id",         id)
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM organizations WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE organizations SET org_status = :status::org_status WHERE id = :id")
                .param("status", status)
                .param("id",     id)
                .update();
    }

    static Organization mapRow(ResultSet rs, int rowNum) throws SQLException {
        String adminUserIdStr = rs.getString("admin_user_id");
        return new Organization(
                UUID.fromString(rs.getString("id")),
                rs.getString("org_name"),
                rs.getString("rut_company"),
                rs.getString("org_email"),
                rs.getString("phone_number"),
                rs.getString("org_status"),
                adminUserIdStr != null ? UUID.fromString(adminUserIdStr) : null,
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
