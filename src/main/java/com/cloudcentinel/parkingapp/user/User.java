package com.cloudcentinel.parkingapp.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una fila de la tabla {@code users}.
 *
 * <p>El campo {@code rut} es el nombre de usuario en Cognito. Debe almacenarse
 * en la base de datos porque {@code AdminUserGlobalSignOut} requiere el username,
 * y buscarlo en Cognito por {@code cognitoSub} implicaría un {@code ListUsers}
 * costoso. Requiere la columna:
 * {@code ALTER TABLE users ADD COLUMN rut VARCHAR(20) NOT NULL UNIQUE;}</p>
 *
 * <p>Los campos {@code role} y {@code userStatus} son enums en PostgreSQL
 * ({@code user_role}, {@code user_status}). Se leen como {@code String} via
 * {@code role::text} en el SQL para no acoplar el record a tipos JDBC no estándar.</p>
 *
 * @param id          UUID interno. PK de la tabla.
 * @param cognitoSub  Identificador permanente del usuario en Cognito.
 *                    FK usada para relacionar el JWT con la fila de base de datos.
 * @param rut         Username en Cognito en forma canónica ({@code "XXXXXXXX-D"}).
 * @param emailAddr   Email del usuario. Columna llamada {@code email_addr} para
 *                    evitar la palabra reservada {@code email} en algunas versiones de PG.
 * @param givenName   Nombre de pila.
 * @param familyName  Apellido.
 * @param phoneNumber Teléfono en formato E.164 (opcional).
 * @param role        Rol del usuario: {@code ADMIN}, {@code CUSTOMER} u {@code OPERATOR}.
 * @param userStatus  Estado del usuario: {@code ACTIVE}, {@code INACTIVE} o {@code SUSPENDED}.
 * @param orgId       FK a {@code organizations.id}. Nulo para usuarios ADMIN de plataforma.
 * @param locationId  FK a {@code locations.id}. Solo poblado para OPERATOR.
 * @param createdAt   Timestamp de creación.
 */
public record User(
        UUID id,
        String cognitoSub,
        String rut,
        String emailAddr,
        String givenName,
        String familyName,
        String phoneNumber,
        String role,
        String userStatus,
        UUID orgId,
        UUID locationId,
        Instant createdAt
) {}
