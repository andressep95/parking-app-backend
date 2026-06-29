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
| Despliegue | Docker + Dokploy |

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
| `CUSTOMER` | Web Dashboard | Gestionar sus OPERATORs y locaciones |
| `OPERATOR` | POS Android | Registrar ingresos/egresos de vehículos en su terminal |

Los roles se gestionan como **grupos Cognito** y viajan en el claim `cognito:groups` del JWT. Spring Security los mapea a `ROLE_ADMIN`, `ROLE_CUSTOMER`, `ROLE_OPERATOR`.

---

## Endpoints

| Módulo | Endpoints |
|---|---|
| **Auth** | `POST /auth/device` · `POST /auth/logout` · `DELETE /auth/sessions/{userId}` |
| **Users** | `POST /users` · `GET /users` · `GET /users/{id}` · `PUT /users/{id}` · `DELETE /users/{id}` · `POST /users/{id}/activate` · `POST /users/{id}/deactivate` · `POST /users/{id}/reset-password` |
| **Organizations** | `POST /organizations` · `GET /organizations` · `GET /organizations/{id}` · `PUT /organizations/{id}` · `DELETE /organizations/{id}` · `POST /organizations/{id}/activate` · `POST /organizations/{id}/deactivate` |
| **Locations** | `POST /locations` · `GET /locations` · `GET /locations/{id}` · `PUT /locations/{id}` · `DELETE /locations/{id}` · `POST /locations/{id}/activate` · `POST /locations/{id}/deactivate` |
| **Terminals** | `POST /terminals` · `GET /terminals` · `GET /terminals/{id}` · `PUT /terminals/{id}` · `DELETE /terminals/{id}` · `POST /terminals/{id}/maintenance` · `POST /terminals/{id}/offline` |
| **Tariffs** | `POST /tariffs` · `GET /tariffs` · `GET /tariffs/{id}` · `POST /tariffs/{id}/deactivate` |
| **Shifts** | `POST /shifts` · `POST /shifts/{id}/close` · `GET /shifts` · `GET /shifts/{id}` |
| **Parking** | `POST /parking-sessions` · `POST /parking-sessions/{id}/checkout` |
| **POS** | `GET /pos/bootstrap/{serialNumber}` · `GET /pos/location-state` |

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
│   ├── AuthController.java          POST /auth/device · POST /auth/logout · DELETE /auth/sessions/{id}
│   ├── AuthService.java             Proxy AdminInitiateAuth + enforce sesión única
│   ├── BootstrapController.java     Endpoint de bootstrap de configuración inicial
│   ├── DeviceLoginRequest.java
│   └── TokenResponse.java
│
├── session/                         ← Tabla user_sessions (sesión única por operador)
│   ├── SessionRepository.java
│   └── UserSession.java
│
├── user/                            ← Tabla users + provisionamiento Cognito
│   ├── UserController.java
│   ├── UserService.java
│   ├── UserRepository.java
│   └── User.java
│
├── organization/                    ← Tabla organizations
│   ├── OrganizationController.java
│   ├── OrganizationService.java
│   ├── OrganizationRepository.java
│   └── Organization.java
│
├── location/                        ← Tabla locations
│   ├── LocationController.java
│   ├── LocationService.java
│   ├── LocationRepository.java
│   └── Location.java
│
├── terminal/                        ← Tabla terminals
│   ├── TerminalController.java
│   ├── TerminalService.java
│   ├── TerminalRepository.java
│   └── Terminal.java
│
├── tariff/                          ← Tabla tariffs (inmutables, solo se desactivan)
│   ├── TariffController.java
│   ├── TariffService.java           Rotate: desactiva anterior + inserta nueva
│   ├── TariffRepository.java
│   └── Tariff.java
│
├── shift/                           ← Tabla shifts (turno activo del operador)
│   ├── ShiftController.java
│   ├── ShiftService.java
│   ├── ShiftRepository.java
│   └── Shift.java
│
├── parking/                         ← Tabla parking_sessions
│   ├── ParkingSessionController.java
│   ├── ParkingSessionService.java   Idempotencia por UUID del POS + tariff snapshot
│   ├── ParkingSessionRepository.java
│   └── TariffSnapshot.java          JSONB snapshot de tarifa al momento del ingreso
│
├── transaction/                     ← Tabla transactions (1:1 con parking_session)
│   └── TransactionRepository.java
│
├── vehicle/                         ← Tabla vehicles (upsert por placa)
│   └── VehicleRepository.java
│
├── pos/                             ← Endpoints exclusivos del POS Android
│   ├── PosController.java           GET /pos/bootstrap/{sn} · GET /pos/location-state
│   ├── PosBootstrapService.java     Vista desnormalizada para carga inicial offline
│   ├── PosLocationStateService.java Delta polling multi-terminal
│   ├── BootstrapResponse.java
│   └── LocationStateResponse.java
│
└── shared/
    ├── cognito/
    │   ├── CognitoAdminClient.java  ← Wrapper de operaciones admin AWS Cognito
    │   └── JwtUtils.java
    ├── rut/
    │   └── RutUtils.java            ← Validación RUT chileno (módulo 11)
    └── exception/
        ├── GlobalExceptionHandler.java
        ├── NotFoundException.java
        ├── ConflictException.java
        ├── ForbiddenException.java
        ├── BadRequestException.java
        └── UnauthorizedException.java
```

---

## Configuración

Copia `.env.example` a `.env` y completa los valores:

```bash
cp .env.example .env
```

Variables requeridas:

```properties
# PostgreSQL
DB_URL=jdbc:postgresql://localhost:5432/parking_app
DB_USERNAME=
DB_PASSWORD=

# AWS Cognito
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
COGNITO_USER_POOL_ID=
COGNITO_CLIENT_ID=
```

---

## Desarrollo local

```bash
# Levantar PostgreSQL con datos de prueba
docker-compose up -d

# Ejecutar la app (perfil local)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Build (JAR ejecutable)
./mvnw package -DskipTests
```

El perfil `local` usa `application-local.properties` y apunta a la BD del `docker-compose`.

---

## Documentación

Las historias de usuario con criterios de aceptación, diagramas de secuencia y contratos de API están en:

```
docs/user-stories/
├── US-001-login-operador-pos.md
├── US-002-logout-operador.md
├── US-003-cierre-sesion-admin.md
├── US-004-registro-usuario.md
├── US-005-crud-usuarios.md
├── US-006-crud-organizaciones.md
├── US-007-crud-locaciones.md
├── US-008-crud-terminales.md
├── US-009-gestion-tarifas.md
├── US-010-bootstrap-pos.md
├── US-011-gestion-turno.md
├── US-012-ingreso-vehiculo.md
├── US-013-cobro-salida.md
└── US-014-estado-locacion.md
```
