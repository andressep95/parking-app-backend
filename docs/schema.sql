-- =============================================================================
-- parking-app — PostgreSQL Schema
-- Migrado desde DynamoDB single-table design
-- PostgreSQL 15+
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ENUMs
-- -----------------------------------------------------------------------------

CREATE TYPE user_role AS ENUM ('ADMIN', 'CUSTOMER', 'OPERATOR');
CREATE TYPE user_status AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');
CREATE TYPE org_status AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');
CREATE TYPE location_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE terminal_status AS ENUM ('ONLINE', 'OFFLINE', 'MAINTENANCE');
CREATE TYPE vehicle_type AS ENUM ('CAR', 'MOTORCYCLE', 'PICKUP', 'BUS');
CREATE TYPE shift_status AS ENUM ('ACTIVE', 'CLOSED');
CREATE TYPE parking_status AS ENUM ('ACTIVE', 'COMPLETED', 'CANCELLED');
CREATE TYPE payment_method AS ENUM ('TUU_CARD', 'TUU_CASH', 'TUU_TRANSFER');

-- -----------------------------------------------------------------------------
-- 1. organizations
-- DynamoDB: ORGANIZATION#<id> / #METADATA
-- AP02, AP03, AP04
-- -----------------------------------------------------------------------------

CREATE TABLE organizations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_name        VARCHAR(255) NOT NULL,
    rut_company     VARCHAR(12)  NOT NULL UNIQUE,
    org_email       VARCHAR(255) NOT NULL,
    phone_number    VARCHAR(20),
    org_status      org_status  NOT NULL DEFAULT 'ACTIVE',
    admin_user_id   UUID,       -- FK a users, se actualiza tras crear el CUSTOMER
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- 2. users
-- DynamoDB: USER#<id> / #METADATA
-- GSI1: COGNITO#<cognito_sub> → AP05 (hot path)
-- GSI2: EMAIL#<email> → AP06
-- AP05, AP06, AP07, AP08
-- -----------------------------------------------------------------------------

CREATE TABLE users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cognito_sub     VARCHAR(255) NOT NULL UNIQUE,   -- AP05 hot path
    email_addr      VARCHAR(255) NOT NULL UNIQUE,   -- AP06
    given_name      VARCHAR(100) NOT NULL,
    family_name     VARCHAR(100) NOT NULL,
    phone_number    VARCHAR(20),
    role            user_role   NOT NULL,
    user_status     user_status NOT NULL DEFAULT 'ACTIVE',
    org_id          UUID        REFERENCES organizations(id) ON DELETE RESTRICT,
    location_id     UUID,                           -- FK a locations (nullable, solo CUSTOMER_OPERATOR)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_operator_has_location
        CHECK (role != 'OPERATOR' OR location_id IS NOT NULL)
);

-- FK circular: organizations.admin_user_id → users
ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_admin_user
    FOREIGN KEY (admin_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- AP05: lookup por cognito_sub (hot path — ya cubierto por UNIQUE arriba)
-- AP06: lookup por email (ya cubierto por UNIQUE arriba)
-- AP08: listar operadores de una organización
CREATE INDEX idx_users_org_id ON users(org_id);
CREATE INDEX idx_users_org_role ON users(org_id, role);

-- -----------------------------------------------------------------------------
-- 3. user_sessions
-- DynamoDB: USER#<id> / SESSION#ACTIVE (SK fijo — garantiza sesión única)
-- Equivalente: UNIQUE en user_id (máx 1 sesión activa por usuario)
-- TTL DynamoDB → expires_at + limpieza lazy en aplicación
-- AP09, AP10, AP10b
-- -----------------------------------------------------------------------------

CREATE TABLE user_sessions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL UNIQUE     -- un solo registro activo por usuario
                                REFERENCES users(id) ON DELETE CASCADE,
    terminal_id     UUID,                           -- FK a terminals (nullable: login web no tiene terminal)
    ip_address      INET,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL            -- reemplaza TTL de DynamoDB
);

CREATE INDEX idx_user_sessions_expires_at ON user_sessions(expires_at);

-- -----------------------------------------------------------------------------
-- 4. locations
-- DynamoDB: LOCATION#<id> / #METADATA + ORGANIZATION#<id> / LOCATION#<location_id>
-- AP11, AP12
-- -----------------------------------------------------------------------------

CREATE TABLE locations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID        NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    location_name   VARCHAR(255) NOT NULL,
    address         TEXT        NOT NULL,
    city            VARCHAR(100) NOT NULL,
    timezone        VARCHAR(50)  NOT NULL DEFAULT 'America/Santiago',
    location_status location_status NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- AP11: listar sedes de una organización
CREATE INDEX idx_locations_org_id ON locations(org_id);

-- FK diferida: users.location_id → locations (evita ciclo en creación)
ALTER TABLE users
    ADD CONSTRAINT fk_users_location
    FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT;

-- -----------------------------------------------------------------------------
-- 5. terminals
-- DynamoDB: TERMINAL#<id> / #METADATA + LOCATION#<id> / TERMINAL#<id>
-- GSI1: SERIAL#<serial_number> → AP13 (hot path)
-- AP13, AP14, AP15
-- -----------------------------------------------------------------------------

CREATE TABLE terminals (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number       VARCHAR(100) NOT NULL UNIQUE,   -- AP13 hot path
    model               VARCHAR(100) NOT NULL,
    org_id              UUID        NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    location_id         UUID        NOT NULL REFERENCES locations(id) ON DELETE RESTRICT,
    status              terminal_status NOT NULL DEFAULT 'OFFLINE',
    active_operator_id  UUID        REFERENCES users(id) ON DELETE SET NULL,
    app_version         VARCHAR(20),
    last_heartbeat      TIMESTAMPTZ
);

-- AP14: listar terminales de una sede
CREATE INDEX idx_terminals_location_id ON terminals(location_id);

-- FK diferida: user_sessions.terminal_id → terminals
ALTER TABLE user_sessions
    ADD CONSTRAINT fk_user_sessions_terminal
    FOREIGN KEY (terminal_id) REFERENCES terminals(id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 6. tariffs
-- DynamoDB: LOCATION#<id> / TARIFF#<vehicle_type> (activo)
--           TARIFF_HISTORY#<location_id> / <vehicle_type>#<valid_from> (histórico)
-- Equivalente: tabla única con is_active + partial UNIQUE index
-- AP16 (hot path), AP17
-- -----------------------------------------------------------------------------

CREATE TABLE tariffs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id     UUID        NOT NULL REFERENCES locations(id) ON DELETE RESTRICT,
    vehicle_type    vehicle_type NOT NULL,
    name            VARCHAR(255),
    price_per_hour  NUMERIC(10,2) NOT NULL CHECK (price_per_hour >= 0),
    minimum_charge  NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (minimum_charge >= 0),
    grace_minutes   INTEGER     NOT NULL DEFAULT 0 CHECK (grace_minutes >= 0),
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ             -- NULL = vigente actualmente
);

-- AP16: una sola tarifa ACTIVA por (location, vehicle_type) — garantía de unicidad
-- Reemplaza el patrón DynamoDB de sobrescribir el mismo ítem
CREATE UNIQUE INDEX idx_tariffs_active_unique
    ON tariffs(location_id, vehicle_type)
    WHERE is_active = TRUE;

-- AP17: listar todas las tarifas (activas e históricas) de una sede
CREATE INDEX idx_tariffs_location_id ON tariffs(location_id);

-- -----------------------------------------------------------------------------
-- 7. vehicles
-- DynamoDB: VEHICLE#<plate> / #METADATA (PK directa — GetItem O(1))
-- AP18
-- -----------------------------------------------------------------------------

CREATE TABLE vehicles (
    plate           VARCHAR(10)  PRIMARY KEY,        -- patente es la clave natural
    vehicle_type    vehicle_type NOT NULL,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_sessions  INTEGER     NOT NULL DEFAULT 0 CHECK (total_sessions >= 0)
);

-- -----------------------------------------------------------------------------
-- 8. shifts
-- DynamoDB: SHIFT#<id>/#METADATA + OPERATOR#<id>/SHIFT#ACTIVE (SK fijo)
-- Equivalente: partial UNIQUE index WHERE status = 'ACTIVE'
-- AP24 (hot path), AP25, AP26
-- -----------------------------------------------------------------------------

CREATE TABLE shifts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id     UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    terminal_id     UUID        NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
    location_id     UUID        NOT NULL REFERENCES locations(id) ON DELETE RESTRICT,
    status          shift_status NOT NULL DEFAULT 'ACTIVE',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    opening_cash    NUMERIC(12,2) NOT NULL DEFAULT 0,
    closing_cash    NUMERIC(12,2)
);

-- AP24: shift ACTIVO de un operador — O(1), equivale al SK fijo SESSION#ACTIVE
-- Garantiza que un operador no puede tener dos turnos activos simultáneos
CREATE UNIQUE INDEX idx_shifts_operator_active
    ON shifts(operator_id)
    WHERE status = 'ACTIVE';

-- AP26: historial de turnos de un operador
CREATE INDEX idx_shifts_operator_id ON shifts(operator_id, started_at DESC);

-- AP27: parking sessions de un turno (cubierto por FK en parking_sessions)
-- AP28: transactions de un turno (cubierto por FK en transactions)

-- -----------------------------------------------------------------------------
-- 9. parking_sessions
-- DynamoDB: PARKING#<id>/#METADATA + LOCATION#<id>/PARKING#ACTIVE#<id>
--           + LOCATION#<id>/PARKING#<YYYY-MM-DD>#<id>
-- GSI1 sparse: PLATE#<plate> → AP20 (hot path)
-- AP19 (hot path), AP20 (hot path), AP21, AP22, AP23, AP27, AP29
-- -----------------------------------------------------------------------------

CREATE TABLE parking_sessions (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id         UUID        NOT NULL REFERENCES locations(id) ON DELETE RESTRICT,
    plate               VARCHAR(10) NOT NULL REFERENCES vehicles(plate) ON DELETE RESTRICT,
    vehicle_type        vehicle_type NOT NULL,
    status              parking_status NOT NULL DEFAULT 'ACTIVE',
    entry_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    exit_at             TIMESTAMPTZ,
    duration_minutes    INTEGER     CHECK (duration_minutes >= 0),
    operator_id_in      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    terminal_id_in      UUID        NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
    shift_id            UUID        NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
    tariff_snapshot     JSONB       NOT NULL,       -- {price_per_hour, minimum_charge, grace_minutes}
    calculated_amount   NUMERIC(12,2) CHECK (calculated_amount >= 0),

    CONSTRAINT chk_exit_after_entry
        CHECK (exit_at IS NULL OR exit_at > entry_at),
    CONSTRAINT chk_completed_has_exit
        CHECK (status = 'ACTIVE' OR exit_at IS NOT NULL)
);

-- AP19: sesiones ACTIVAS de una sede (dashboard tiempo real)
CREATE INDEX idx_parking_location_status
    ON parking_sessions(location_id, status);

-- AP20: sesión ACTIVA por patente (hot path — cobro/salida)
-- Equivale al GSI1 sparse de DynamoDB (solo ítems ACTIVE tenían GSI1PK)
CREATE INDEX idx_parking_plate_active
    ON parking_sessions(plate)
    WHERE status = 'ACTIVE';

-- AP29: sesiones por sede + rango de fechas (reportes históricos)
CREATE INDEX idx_parking_location_entry_at
    ON parking_sessions(location_id, entry_at DESC);

-- AP27: sesiones de un turno (cierre de caja)
CREATE INDEX idx_parking_shift_id
    ON parking_sessions(shift_id, entry_at DESC);

-- -----------------------------------------------------------------------------
-- 10. transactions
-- DynamoDB: TRANSACTION#<id>/#METADATA + PARKING#<id>/TRANSACTION#<id>
--           + SHIFT#<id>/TRANSACTION#<at>#<id>
-- AP28
-- -----------------------------------------------------------------------------

CREATE TABLE transactions (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    parking_session_id  UUID        NOT NULL UNIQUE  -- 1:1 con parking_session
                                    REFERENCES parking_sessions(id) ON DELETE RESTRICT,
    shift_id            UUID        NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
    amount              NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    payment_method      payment_method NOT NULL,
    transaction_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tuu_reference       VARCHAR(255)            -- referencia externa del dispositivo TUU
);

-- AP28: transactions de un turno (cierre de caja)
CREATE INDEX idx_transactions_shift_id
    ON transactions(shift_id, transaction_at DESC);

-- -----------------------------------------------------------------------------
-- 11. audit_logs
-- DynamoDB: AUDIT#<entity>#<entity_id> / <occurred_at>#<audit_id>
-- GSI2: AUDIT_USER#<user_id> → AP32
-- AP31, AP32
-- Inmutable por diseño — no se hacen UPDATE ni DELETE sobre esta tabla
-- -----------------------------------------------------------------------------

CREATE TABLE audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action      VARCHAR(100) NOT NULL,
    entity      VARCHAR(50)  NOT NULL,
    entity_id   UUID        NOT NULL,
    details     JSONB,                          -- {before: {...}, after: {...}}
    ip_address  INET,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- AP31: audit log por entidad + rango de fechas
CREATE INDEX idx_audit_logs_entity
    ON audit_logs(entity, entity_id, occurred_at DESC);

-- AP32: audit log por user_id + rango de fechas (equivale al GSI2 AUDIT_USER#)
CREATE INDEX idx_audit_logs_user_id
    ON audit_logs(user_id, occurred_at DESC);

-- =============================================================================
-- Notas de equivalencia DynamoDB → PostgreSQL
-- =============================================================================
--
-- 1. SK fijo SHIFT#ACTIVE / SESSION#ACTIVE
--    → Partial UNIQUE INDEX WHERE status = 'ACTIVE'
--    Garantía equivalente: a lo sumo 1 registro activo por operador/usuario
--
-- 2. GSI1 overloading (COGNITO#, SERIAL#, PLATE#)
--    → Índices individuales: users(cognito_sub), terminals(serial_number),
--      parking_sessions(plate) WHERE ACTIVE
--
-- 3. Sparse index DynamoDB (solo ítems activos en GSI1)
--    → Partial index PostgreSQL: idx_parking_plate_active WHERE status='ACTIVE'
--
-- 4. TTL de UserSession (DynamoDB eventual, hasta 48h)
--    → expires_at + limpieza en app (DELETE WHERE expires_at < now())
--      o un job periódico. Limpieza lazy en cada login es suficiente a esta escala.
--
-- 5. Tarifa activa vs historial (2 ítems separados en DynamoDB)
--    → Tabla única tariffs con is_active BOOLEAN
--      Al cambiar tarifa: UPDATE SET is_active=FALSE, valid_until=now()
--                       + INSERT nueva tarifa con is_active=TRUE
--
-- 6. TransactWrite multi-ítem
--    → BEGIN / COMMIT de PostgreSQL — más simple, sin límite de 100 ítems
--
-- 7. Snapshot de tarifa en ParkingSession
--    → tariff_snapshot JSONB — mismo propósito: precio congelado al momento del ingreso
--
-- =============================================================================
