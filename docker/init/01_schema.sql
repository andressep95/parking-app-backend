-- =============================================================================
-- parking-app — PostgreSQL Schema
-- Migrado desde DynamoDB single-table design · PostgreSQL 15+
-- =============================================================================

-- ENUMs -----------------------------------------------------------------------

CREATE TYPE user_role       AS ENUM ('ADMIN', 'CUSTOMER', 'OPERATOR');
CREATE TYPE user_status     AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');
CREATE TYPE org_status      AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');
CREATE TYPE location_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE terminal_status AS ENUM ('ONLINE', 'OFFLINE', 'MAINTENANCE');
CREATE TYPE vehicle_type    AS ENUM ('CAR', 'MOTORCYCLE', 'PICKUP', 'BUS');
CREATE TYPE shift_status    AS ENUM ('ACTIVE', 'CLOSED');
CREATE TYPE parking_status  AS ENUM ('ACTIVE', 'COMPLETED', 'CANCELLED');
CREATE TYPE payment_method  AS ENUM ('TUU_CARD', 'TUU_CASH', 'TUU_TRANSFER');

-- 1. organizations ------------------------------------------------------------

CREATE TABLE organizations (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_name      VARCHAR(255) NOT NULL,
    rut_company   VARCHAR(12)  NOT NULL UNIQUE,
    org_email     VARCHAR(255) NOT NULL,
    phone_number  VARCHAR(20),
    org_status    org_status   NOT NULL DEFAULT 'ACTIVE',
    admin_user_id UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2. users --------------------------------------------------------------------
-- rut: username en Cognito (forma canónica XXXXXXXX-D).
-- Necesario para AdminUserGlobalSignOut, que requiere el username del user pool.

CREATE TABLE users (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cognito_sub  VARCHAR(255) NOT NULL UNIQUE,
    rut          VARCHAR(20)  NOT NULL UNIQUE,
    email_addr   VARCHAR(255) NOT NULL UNIQUE,
    given_name   VARCHAR(100) NOT NULL,
    family_name  VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20),
    role         user_role    NOT NULL,
    user_status  user_status  NOT NULL DEFAULT 'ACTIVE',
    org_id       UUID         REFERENCES organizations(id) ON DELETE RESTRICT,
    location_id  UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_operator_has_location
        CHECK (role != 'OPERATOR' OR location_id IS NOT NULL)
);

ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_admin_user
    FOREIGN KEY (admin_user_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_org_id   ON users(org_id);
CREATE INDEX idx_users_org_role ON users(org_id, role);

-- 3. user_sessions ------------------------------------------------------------

CREATE TABLE user_sessions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    terminal_id UUID,
    ip_address  INET,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_user_sessions_expires_at ON user_sessions(expires_at);

-- 4. locations ----------------------------------------------------------------

CREATE TABLE locations (
    id              UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID             NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    location_name   VARCHAR(255)     NOT NULL,
    address         TEXT             NOT NULL,
    city            VARCHAR(100)     NOT NULL,
    capacity        INTEGER          NOT NULL DEFAULT 0 CHECK (capacity >= 0),
    max_operators   INTEGER          NOT NULL DEFAULT 0 CHECK (max_operators >= 0),
    timezone        VARCHAR(50)      NOT NULL DEFAULT 'America/Santiago',
    location_status location_status  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_locations_org_id ON locations(org_id);

ALTER TABLE users
    ADD CONSTRAINT fk_users_location
    FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT;

-- 5. terminals ----------------------------------------------------------------

CREATE TABLE terminals (
    id                 UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number      VARCHAR(100)    NOT NULL UNIQUE,
    model              VARCHAR(100)    NOT NULL,
    org_id             UUID            NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    status             terminal_status NOT NULL DEFAULT 'OFFLINE',
    active_operator_id UUID            REFERENCES users(id) ON DELETE SET NULL,
    app_version        VARCHAR(20),
    last_heartbeat     TIMESTAMPTZ
);

ALTER TABLE user_sessions
    ADD CONSTRAINT fk_user_sessions_terminal
    FOREIGN KEY (terminal_id) REFERENCES terminals(id) ON DELETE SET NULL;

-- 6. tariffs ------------------------------------------------------------------

CREATE TABLE tariffs (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id    UUID          NOT NULL REFERENCES locations(id) ON DELETE RESTRICT,
    vehicle_type   vehicle_type  NOT NULL,
    name           VARCHAR(255),
    price_per_hour NUMERIC(10,2) NOT NULL CHECK (price_per_hour >= 0),
    minimum_charge NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (minimum_charge >= 0),
    grace_minutes  INTEGER       NOT NULL DEFAULT 0 CHECK (grace_minutes >= 0),
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    valid_from     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    valid_until    TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tariffs_active_unique
    ON tariffs(location_id, vehicle_type) WHERE is_active = TRUE;

CREATE INDEX idx_tariffs_location_id ON tariffs(location_id);

-- 7. vehicles -----------------------------------------------------------------

CREATE TABLE vehicles (
    plate          VARCHAR(10)  PRIMARY KEY,
    vehicle_type   vehicle_type NOT NULL,
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    total_sessions INTEGER      NOT NULL DEFAULT 0 CHECK (total_sessions >= 0)
);

-- 8. shifts -------------------------------------------------------------------

CREATE TABLE shifts (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id  UUID         NOT NULL REFERENCES users(id)      ON DELETE RESTRICT,
    terminal_id  UUID         NOT NULL REFERENCES terminals(id)  ON DELETE RESTRICT,
    location_id  UUID         NOT NULL REFERENCES locations(id)  ON DELETE RESTRICT,
    status       shift_status NOT NULL DEFAULT 'ACTIVE',
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at    TIMESTAMPTZ,
    opening_cash NUMERIC(12,2) NOT NULL DEFAULT 0,
    closing_cash NUMERIC(12,2)
);

CREATE UNIQUE INDEX idx_shifts_operator_active
    ON shifts(operator_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_shifts_operator_id ON shifts(operator_id, started_at DESC);

-- 9. parking_sessions ---------------------------------------------------------

CREATE TABLE parking_sessions (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id       UUID           NOT NULL REFERENCES locations(id)  ON DELETE RESTRICT,
    plate             VARCHAR(10)    NOT NULL REFERENCES vehicles(plate) ON DELETE RESTRICT,
    vehicle_type      vehicle_type   NOT NULL,
    status            parking_status NOT NULL DEFAULT 'ACTIVE',
    entry_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    exit_at           TIMESTAMPTZ,
    duration_minutes  INTEGER        CHECK (duration_minutes >= 0),
    operator_id_in    UUID           NOT NULL REFERENCES users(id)      ON DELETE RESTRICT,
    terminal_id_in    UUID           NOT NULL REFERENCES terminals(id)  ON DELETE RESTRICT,
    shift_id          UUID           NOT NULL REFERENCES shifts(id)     ON DELETE RESTRICT,
    tariff_snapshot   JSONB          NOT NULL,
    calculated_amount NUMERIC(12,2)  CHECK (calculated_amount >= 0),

    CONSTRAINT chk_exit_after_entry    CHECK (exit_at IS NULL OR exit_at > entry_at),
    CONSTRAINT chk_completed_has_exit  CHECK (status = 'ACTIVE' OR exit_at IS NOT NULL)
);

CREATE INDEX idx_parking_location_status  ON parking_sessions(location_id, status);
CREATE INDEX idx_parking_plate_active     ON parking_sessions(plate) WHERE status = 'ACTIVE';
CREATE INDEX idx_parking_location_entry_at ON parking_sessions(location_id, entry_at DESC);
CREATE INDEX idx_parking_shift_id         ON parking_sessions(shift_id, entry_at DESC);

-- 10. transactions ------------------------------------------------------------

CREATE TABLE transactions (
    id                 UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    parking_session_id UUID           NOT NULL UNIQUE REFERENCES parking_sessions(id) ON DELETE RESTRICT,
    shift_id           UUID           NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
    amount             NUMERIC(12,2)  NOT NULL CHECK (amount >= 0),
    payment_method     payment_method NOT NULL,
    transaction_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    tuu_reference      VARCHAR(255)
);

CREATE INDEX idx_transactions_shift_id ON transactions(shift_id, transaction_at DESC);

-- 11. audit_logs --------------------------------------------------------------

CREATE TABLE audit_logs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action      VARCHAR(100) NOT NULL,
    entity      VARCHAR(50)  NOT NULL,
    entity_id   UUID         NOT NULL,
    details     JSONB,
    ip_address  INET,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_entity  ON audit_logs(entity, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id, occurred_at DESC);
