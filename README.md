# parking-app-backend

Backend central del sistema de gestión de estacionamientos para terminales **TUU Haulmer**. Expone una API REST que sirve tanto al cliente nativo Android (POS) como al panel web (Dashboard). Actúa como proxy de autenticación hacia AWS Cognito y como única fuente de verdad de identidad.

---

## Stack

| Capa | Tecnología |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4.1 |
| Seguridad | Spring Security + OAuth2 Resource Server |
| Identidad | AWS Cognito (User Pool) |
| Base de datos | PostgreSQL 15+ |
| Acceso a datos | Spring JDBC (`JdbcClient`) |
| AWS SDK | AWS SDK for Java v2 (`cognitoidentityprovider`) |

---

## Jerarquía de Roles

```
ADMIN  (plataforma)
  └── CUSTOMER  (empresa dueña del estacionamiento)
        └── OPERATOR  (trabajador de campo en terminal POS)
```

| Rol | Canal | Capacidad |
|---|---|---|
| `ADMIN` | Web Dashboard | Registrar/suspender CUSTOMERs, ver datos globales |
| `CUSTOMER` | Web Dashboard | Registrar/suspender sus OPERATORs, ver sus propios datos |
| `OPERATOR` | POS Android | Ejecutar cobros y registros de vehículos en su terminal asignada |

Los roles se gestionan como **grupos Cognito** y viajan en el claim `cognito:groups` del JWT. Spring Security los mapea automáticamente a `ROLE_ADMIN`, `ROLE_CUSTOMER`, `ROLE_OPERATOR`.

---

## Estructura de Paquetes

```
src/main/java/com/cloudcentinel/parkingapp/
│
├── config/
│   ├── SecurityConfig.java          ← SecurityFilterChain, JwtDecoder, authority mapping
│   └── CognitoAdminConfig.java      ← Bean CognitoIdentityProviderClient (AWS SDK v2)
│
├── auth/                            ← Autenticación mediada para POS Android
│   ├── AuthController.java          POST /auth/device  |  POST /auth/logout
│   ├── AuthService.java             Proxy AdminInitiateAuth + enforce sesión única
│   ├── DeviceLoginRequest.java      record { rut, password, serialNumber }
│   └── TokenResponse.java           record { accessToken, refreshToken, expiresIn }
│
├── session/                         ← Tabla user_sessions (sesión única por usuario)
│   ├── SessionRepository.java       Crear / revocar / limpiar sesiones expiradas
│   └── UserSession.java             record
│
├── user/                            ← Tabla users + provisionamiento Cognito
│   ├── UserController.java          CRUD de usuarios por rol
│   ├── UserService.java             AdminCreateUser + AdminAddUserToGroup + INSERT users
│   ├── UserRepository.java          JdbcClient queries
│   └── User.java                    record { id, cognitoSub, rut, email, role, ... }
│
├── organization/                    ← Tabla organizations
│   ├── OrganizationController.java
│   ├── OrganizationService.java
│   ├── OrganizationRepository.java
│   └── Organization.java            record { id, orgName, rutCompany, email, status, ... }
│
├── location/                        ← Tabla locations (sedes de una organización)
│   ├── LocationController.java
│   ├── LocationService.java
│   ├── LocationRepository.java
│   └── Location.java                record { id, orgId, locationName, address, city, ... }
│
├── terminal/                        ← Tabla terminals (binding serial → ubicación)
│   ├── TerminalController.java
│   ├── TerminalService.java
│   ├── TerminalRepository.java
│   └── Terminal.java                record { id, serialNumber, locationId, status, ... }
│
├── shift/                           ← Tabla shifts (turno activo del operador)
│   ├── ShiftController.java         Abrir / cerrar turno
│   ├── ShiftService.java            Garantiza un único turno ACTIVE por operador
│   ├── ShiftRepository.java
│   └── Shift.java                   record { id, operatorId, terminalId, status, ... }
│
├── parking/                         ← Tabla parking_sessions
│   ├── ParkingController.java       Entrada / salida de vehículo, sesiones activas
│   ├── ParkingService.java          Cálculo de tarifa + snapshot en JSONB
│   ├── ParkingRepository.java
│   └── ParkingSession.java          record { id, plate, vehicleType, status, tariffSnapshot, ... }
│
├── tariff/                          ← Tabla tariffs (historial de tarifas por sede)
│   ├── TariffController.java
│   ├── TariffService.java           Rotate: desactiva tarifa anterior + inserta nueva
│   ├── TariffRepository.java
│   └── Tariff.java                  record { id, locationId, vehicleType, pricePerHour, isActive, ... }
│
├── transaction/                     ← Tabla transactions (1:1 con parking_session)
│   ├── TransactionController.java
│   ├── TransactionService.java
│   ├── TransactionRepository.java
│   └── Transaction.java             record { id, parkingSessionId, amount, paymentMethod, ... }
│
├── audit/                           ← Tabla audit_logs (inmutable, sin endpoint propio)
│   ├── AuditService.java            Llamado internamente por otros Services
│   ├── AuditRepository.java
│   └── AuditLog.java                record { userId, action, entity, entityId, details, ... }
│
└── shared/
    ├── cognito/
    │   └── CognitoAdminClient.java  ← Wrapper de operaciones admin AWS:
    │                                    AdminCreateUser / AdminSetUserPassword
    │                                    AdminAddUserToGroup / AdminUserGlobalSignOut
    └── exception/
        ├── GlobalExceptionHandler.java  @RestControllerAdvice → RFC 7807 Problem Details
        ├── NotFoundException.java
        ├── ConflictException.java
        └── ForbiddenException.java
```

### Decisiones de diseño

| Decisión | Motivo |
|---|---|
| **Vertical slices** — un paquete por dominio | Cada feature tiene su propio controller, service, repository y record. Evita el acoplamiento horizontal de capas genéricas. |
| **`session/` separado de `auth/`** | `SessionRepository` lo usan `auth/` (crear sesión) y futuros endpoints de heartbeat/logout. No pertenece a ninguno de los dos exclusivamente. |
| **`audit/` sin controller** | `AuditService` es llamado internamente por otros `@Service`. Nunca se expone como endpoint directo; los logs son inmutables. |
| **`shared/cognito/CognitoAdminClient`** | Centraliza toda interacción con el AWS SDK. Ningún `@Service` importa el SDK directamente; facilita pruebas con mock del wrapper. |
| **Records Java 21** | Modelos inmutables y sin boilerplate para entidades de dominio y DTOs. |

---

## Implementaciones de referencia (`lambdas/`)

El directorio `lambdas/` contiene las implementaciones previas escritas en **Go** para AWS Lambda + DynamoDB. Están técnicamente completas y funcionan como referencia directa para portar la lógica al stack Spring Boot + PostgreSQL.

| Lambda | Contenido | Equivalente Spring |
|---|---|---|
| `auth-handler/` | Login con RUT + serial binding, registro individual y por lote (Excel), logout, cierre remoto de sesión | `auth/` + `session/` |
| `user-handler/` | CRUD de usuarios, cambio de estado, reset de contraseña, listado por org | `user/` |
| `organization-handler/` | CRUD de organizaciones, cambio de estado | `organization/` |

> Al portar cada handler, reemplazar:
> - Queries DynamoDB (`QueryInput`, `GetItemInput`, `TransactWriteItems`) → `JdbcClient` con SQL parametrizado
> - `AdminInitiateAuth` (flujo `USER_PASSWORD_AUTH`) → se mantiene igual vía `CognitoAdminClient`
> - `TTL` de sesión → columna `expires_at` + limpieza lazy en cada login

---

## Configuración

### `application.properties`

```properties
# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/parking_app
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}

# AWS Cognito
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5Hq8VEHuN
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5Hq8VEHuN/.well-known/jwks.json
aws.cognito.client-id=1d2sil9s35rb6b6t02c647co98
aws.cognito.user-pool-id=us-east-1_5Hq8VEHuN
```

Las credenciales de AWS (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`) se inyectan como variables de entorno; el SDK v2 las detecta automáticamente via `DefaultCredentialsProvider`.

---

## Comandos

```bash
# Compilar
./mvnw compile

# Ejecutar tests
./mvnw test

# Build (JAR ejecutable)
./mvnw package -DskipTests

# Levantar en local
./mvnw spring-boot:run
```
