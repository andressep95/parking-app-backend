-- =============================================================================
-- Migración: modelo dinámico de tarifas
-- Aplicar sobre esquema existente (sin tariff_type, con price_per_hour/minimum_charge)
-- =============================================================================

-- Paso 1: crear el nuevo ENUM
CREATE TYPE tariff_type AS ENUM ('PER_MINUTE', 'BRACKET', 'FLAT_ENTRY');

-- Paso 2: agregar columnas nuevas a tariffs
ALTER TABLE tariffs
    ADD COLUMN tariff_type      tariff_type   NOT NULL DEFAULT 'PER_MINUTE',
    ADD COLUMN price_per_minute NUMERIC(10,4),
    ADD COLUMN max_charge       NUMERIC(10,2),
    ADD COLUMN flat_amount      NUMERIC(10,2);

-- Paso 3: migrar datos existentes
--   price_per_hour  → price_per_minute  (dividir entre 60)
--   minimum_charge era un mínimo de cobro (piso), no un tope.
--   No tiene equivalencia directa en el nuevo modelo: se omite (max_charge queda NULL).
UPDATE tariffs
SET price_per_minute = price_per_hour / 60.0;

-- Paso 4: quitar el DEFAULT temporal en tariff_type (ya está poblado)
ALTER TABLE tariffs
    ALTER COLUMN tariff_type DROP DEFAULT;

-- Paso 5: eliminar columnas obsoletas
ALTER TABLE tariffs
    DROP COLUMN price_per_hour,
    DROP COLUMN minimum_charge;

-- Paso 6: agregar constraints de integridad por tipo
ALTER TABLE tariffs
    ADD CONSTRAINT chk_per_minute_has_rate
        CHECK (tariff_type != 'PER_MINUTE' OR price_per_minute IS NOT NULL),
    ADD CONSTRAINT chk_flat_entry_has_amount
        CHECK (tariff_type != 'FLAT_ENTRY' OR flat_amount IS NOT NULL),
    ADD CONSTRAINT chk_price_per_minute_positive
        CHECK (price_per_minute IS NULL OR price_per_minute >= 0),
    ADD CONSTRAINT chk_max_charge_positive
        CHECK (max_charge IS NULL OR max_charge >= 0),
    ADD CONSTRAINT chk_flat_amount_positive
        CHECK (flat_amount IS NULL OR flat_amount >= 0);

-- Paso 7: crear tabla tariff_brackets
CREATE TABLE tariff_brackets (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tariff_id        UUID          NOT NULL REFERENCES tariffs(id) ON DELETE CASCADE,
    position         SMALLINT      NOT NULL CHECK (position >= 1),
    from_minute      INTEGER       NOT NULL CHECK (from_minute >= 0),
    to_minute        INTEGER       CHECK (to_minute IS NULL OR to_minute > from_minute),
    price_per_minute NUMERIC(10,4) NOT NULL CHECK (price_per_minute >= 0),

    CONSTRAINT uq_tariff_bracket_position UNIQUE (tariff_id, position),
    CONSTRAINT uq_tariff_bracket_from     UNIQUE (tariff_id, from_minute)
);

CREATE INDEX idx_tariff_brackets_tariff_id ON tariff_brackets(tariff_id, position);
