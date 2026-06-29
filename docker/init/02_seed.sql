-- =============================================================================
-- parking-app — Datos de prueba
-- =============================================================================
--
-- ANTES DE USAR:
--   Los tres usuarios deben existir en el Cognito User Pool con los mismos RUTs
--   como username. Una vez creados, reemplaza los cognito_sub de abajo con los
--   valores reales que retorna AdminCreateUser (atributo "sub").
--
-- Credenciales de prueba (crear en Cognito con estos valores):
--
--   ADMIN    → username: 11111111-1  password: Admin.2024#
--   CUSTOMER → username: 22222222-2  password: Customer.2024#
--   OPERATOR → username: 33333333-3  password: Operator.2024#
--
-- Los RUTs 11111111-1, 22222222-2 y 33333333-3 pasan el módulo 11.
--
-- Para probar POST /auth/device (login del POS):
--   {
--     "rut":          "33333333-3",
--     "password":     "Operator.2024#",
--     "serialNumber": "TUU-TEST-001"
--   }
-- =============================================================================

-- 1. Organización de prueba ---------------------------------------------------

INSERT INTO organizations (id, org_name, rut_company, org_email, phone_number, org_status)
VALUES (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'Parking Test SA',
    '76000000-0',
    'contacto@parkingtest.cl',
    '+56912345678',
    'ACTIVE'
);

-- 2. Sede de prueba -----------------------------------------------------------

INSERT INTO locations (id, org_id, location_name, address, city, timezone, location_status)
VALUES (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'Sucursal Centro',
    'Av. Libertador Bernardo O''Higgins 1234',
    'Santiago',
    'America/Santiago',
    'ACTIVE'
);

-- 3. Usuario ADMIN ------------------------------------------------------------
-- cognito_sub: reemplazar con el sub real después de crear en Cognito

INSERT INTO users (id, cognito_sub, rut, email_addr, given_name, family_name, role, user_status)
VALUES (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    '00000000-0000-0000-0000-000000000001',   -- REEMPLAZAR con sub real de Cognito
    '11111111-1',
    'admin@parkingtest.cl',
    'Admin',
    'Sistema',
    'ADMIN',
    'ACTIVE'
);

-- 4. Usuario CUSTOMER ---------------------------------------------------------

INSERT INTO users (id, cognito_sub, rut, email_addr, given_name, family_name, role, user_status, org_id)
VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddddd',
    '00000000-0000-0000-0000-000000000002',   -- REEMPLAZAR con sub real de Cognito
    '22222222-2',
    'customer@parkingtest.cl',
    'Cliente',
    'Prueba',
    'CUSTOMER',
    'ACTIVE',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
);

-- Vincular CUSTOMER como admin de la organización
UPDATE organizations
SET admin_user_id = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

-- 5. Usuario OPERATOR ---------------------------------------------------------
-- OPERATOR requiere location_id (restricción CHECK en el schema)

INSERT INTO users (id, cognito_sub, rut, email_addr, given_name, family_name,
                   role, user_status, org_id, location_id)
VALUES (
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
    '00000000-0000-0000-0000-000000000003',   -- REEMPLAZAR con sub real de Cognito
    '33333333-3',
    'operador@parkingtest.cl',
    'Operador',
    'Prueba',
    'OPERATOR',
    'ACTIVE',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
);

-- 6. Terminal en estado ONLINE ------------------------------------------------
-- serialNumber: "TUU-TEST-001" — usar este valor en el campo serialNumber del login

INSERT INTO terminals (id, serial_number, model, org_id, status)
VALUES (
    'ffffffff-ffff-ffff-ffff-ffffffffffff',
    'TUU-TEST-001',
    'TUU S1',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'ONLINE'
);
