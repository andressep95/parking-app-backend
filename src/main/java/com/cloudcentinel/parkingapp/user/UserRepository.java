package com.cloudcentinel.parkingapp.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a la tabla {@code users} usando {@link JdbcClient}.
 *
 * <p><b>Stub de compilación</b>: este archivo contiene solo los métodos que
 * {@code AuthService} necesita para compilar. Cuando se construya la capa
 * completa de usuario se añadirán los métodos de CRUD, listado y cambio de
 * estado.</p>
 *
 * <h2>RowMapper</h2>
 * <p>Los campos {@code role} y {@code user_status} son enums de PostgreSQL.
 * Se castean a {@code text} en el SQL ({@code role::text}) para que el driver
 * JDBC los lea como {@code String} sin necesidad de un tipo personalizado.</p>
 */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca un usuario por su {@code cognito_sub}.
     *
     * <p>Es el lookup del hot path de autenticación. Después de que Cognito
     * emite tokens, el backend extrae el {@code sub} del JWT y lo usa para
     * encontrar la fila correspondiente en PostgreSQL. El índice {@code UNIQUE}
     * sobre {@code cognito_sub} garantiza O(1) para esta consulta.</p>
     *
     * @param cognitoSub sub del JWT emitido por Cognito.
     * @return el usuario si existe, {@link Optional#empty()} en caso contrario.
     */
    public Optional<User> findByCognitoSub(String cognitoSub) {
        return jdbc.sql("""
                SELECT id, cognito_sub, rut, email_addr, given_name, family_name,
                       phone_number, role::text, user_status::text,
                       org_id, location_id, created_at
                FROM users
                WHERE cognito_sub = :sub
                """)
                .param("sub", cognitoSub)
                .query(UserRepository::mapRow)
                .optional();
    }

    /**
     * Busca un usuario por su RUT (username en Cognito).
     *
     * <p>Usado al inicio del flujo {@code POST /auth/device} para verificar
     * el rol y el estado del usuario <b>antes</b> de llamar a Cognito. Esto
     * evita consumir una llamada de {@code AdminInitiateAuth} para operadores
     * inactivos, roles no autorizados o terminales inválidos.</p>
     *
     * <p>El RUT debe llegar normalizado ({@code "XXXXXXXX-D"}) desde el
     * service, que lo procesa con {@code RutUtils.normalizeAndValidate()}
     * antes de esta consulta.</p>
     *
     * @param rut RUT en forma canónica {@code "XXXXXXXX-D"}.
     * @return el usuario si existe, {@link Optional#empty()} en caso contrario.
     */
    public Optional<User> findByRut(String rut) {
        return jdbc.sql("""
                SELECT id, cognito_sub, rut, email_addr, given_name, family_name,
                       phone_number, role::text, user_status::text,
                       org_id, location_id, created_at
                FROM users
                WHERE rut = :rut
                """)
                .param("rut", rut)
                .query(UserRepository::mapRow)
                .optional();
    }

    /**
     * Busca un usuario por su UUID interno.
     *
     * <p>Usado en el endpoint de cierre remoto de sesión
     * ({@code DELETE /auth/sessions/{userId}}), donde el ADMIN proporciona
     * el ID interno y necesitamos el {@code rut} para llamar a
     * {@code AdminUserGlobalSignOut} en Cognito.</p>
     *
     * @param id PK interna del usuario.
     * @return el usuario si existe, {@link Optional#empty()} en caso contrario.
     */
    public Optional<User> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, cognito_sub, rut, email_addr, given_name, family_name,
                       phone_number, role::text, user_status::text,
                       org_id, location_id, created_at
                FROM users
                WHERE id = :id
                """)
                .param("id", id)
                .query(UserRepository::mapRow)
                .optional();
    }

    static User mapRow(ResultSet rs, int rowNum) throws SQLException {
        String orgIdStr      = rs.getString("org_id");
        String locationIdStr = rs.getString("location_id");
        return new User(
                UUID.fromString(rs.getString("id")),
                rs.getString("cognito_sub"),
                rs.getString("rut"),
                rs.getString("email_addr"),
                rs.getString("given_name"),
                rs.getString("family_name"),
                rs.getString("phone_number"),
                rs.getString("role"),
                rs.getString("user_status"),
                orgIdStr      != null ? UUID.fromString(orgIdStr)      : null,
                locationIdStr != null ? UUID.fromString(locationIdStr) : null,
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
