# auth-handler

Lambda Go para autenticación, registro y gestión de sesiones del sistema de parking.  
Corre en `provided.al2023` / `arm64`. El binario se llama `bootstrap`.

---

## Arquitectura

```
Cliente (Web / Terminal Android)
        │
        ▼
API Gateway HTTP v2
  ├── Público: /login, /register, /register/batch
  └── JWT auth: /logout, /sessions/{user_id}
        │
        ▼
auth-handler (Lambda Go arm64)
  ├── Cognito (AdminCreateUser, InitiateAuth, GlobalSignOut, …)
  └── DynamoDB (USER#, ORGANIZATION#, SESSION#ACTIVE, GSI1/GSI2)
```

---

## Variables de entorno

| Variable | Descripción |
|---|---|
| `COGNITO_USER_POOL_ID` | ID del User Pool |
| `COGNITO_CLIENT_ID` | ID del App Client |
| `DYNAMODB_TABLE_NAME` | Nombre de la tabla DynamoDB principal |

---

## Endpoints

| Método | Ruta | Auth | Descripción |
|--------|------|------|-------------|
| `POST` | `/api/v1/auth/login` | Público | Autenticación con RUT + contraseña |
| `POST` | `/api/v1/auth/register` | Público | Registrar un usuario (y organización si CUSTOMER) |
| `POST` | `/api/v1/auth/register/batch` | Público | Registro masivo desde Excel |
| `POST` | `/api/v1/auth/logout` | JWT | Cerrar sesión propia |
| `DELETE` | `/api/v1/auth/sessions/{user_id}` | JWT (ADMIN) | Cierre remoto de sesión de cualquier usuario |

---

## Structs Go

```go
// ── Login ─────────────────────────────────────────────────────────────────────
type LoginRequest struct {
    RUT          string `json:"rut"`
    Password     string `json:"password"`
    SerialNumber string `json:"serial_number,omitempty"` // requerido para CUSTOMER_OPERATOR
}

type LoginResponse struct {
    AccessToken  string `json:"access_token"`
    IDToken      string `json:"id_token"`
    RefreshToken string `json:"refresh_token"`
    ExpiresIn    int32  `json:"expires_in"`
}

// ── Registro ───────────────────────────────────────────────────────────────────
type RegisterRequest struct {
    RUT         string `json:"rut"`
    Password    string `json:"password"`
    Email       string `json:"email"`
    GivenName   string `json:"given_name"`
    FamilyName  string `json:"family_name"`
    PhoneNumber string `json:"phone_number,omitempty"`
    Role        string `json:"role,omitempty"`        // default: CUSTOMER_OPERATOR
    OrgID       string `json:"org_id,omitempty"`      // para CUSTOMER_OPERATOR
    LocationID  string `json:"location_id,omitempty"`
    OrgName     string `json:"org_name,omitempty"`    // requerido si role == CUSTOMER
    OrgRut      string `json:"org_rut,omitempty"`
}

// ── DynamoDB — User ────────────────────────────────────────────────────────────
type UserItem struct {
    PK         string `dynamodbav:"PK"`          // USER#<id>
    SK         string `dynamodbav:"SK"`          // #METADATA
    GSI1PK     string `dynamodbav:"GSI1PK"`      // COGNITO#<cognito_sub>
    GSI1SK     string `dynamodbav:"GSI1SK"`      // USER#<id>
    GSI2PK     string `dynamodbav:"GSI2PK"`      // EMAIL#<email>
    GSI2SK     string `dynamodbav:"GSI2SK"`      // USER#<id>
    ID         string `dynamodbav:"id"`
    RUT        string `dynamodbav:"rut"`
    Email      string `dynamodbav:"email_addr"`  // 'email' es reservada en DynamoDB
    GivenName  string `dynamodbav:"given_name"`
    FamilyName string `dynamodbav:"family_name"`
    Phone      string `dynamodbav:"phone_number,omitempty"`
    Role       string `dynamodbav:"role"`
    Status     string `dynamodbav:"user_status"` // 'status' es reservada en DynamoDB
    OrgID      string `dynamodbav:"org_id,omitempty"`
    LocationID string `dynamodbav:"location_id,omitempty"`
    CognitoSub string `dynamodbav:"cognito_sub"`
    CreatedAt  string `dynamodbav:"created_at"`
}

// ── DynamoDB — Org ─────────────────────────────────────────────────────────────
type OrgItem struct {
    PK          string `dynamodbav:"PK"`           // ORGANIZATION#<id>
    SK          string `dynamodbav:"SK"`           // #METADATA
    ID          string `dynamodbav:"id"`
    OrgName     string `dynamodbav:"org_name"`     // 'name' es reservada en DynamoDB
    RutEmpresa  string `dynamodbav:"rut_empresa,omitempty"`
    Email       string `dynamodbav:"org_email,omitempty"`
    Phone       string `dynamodbav:"phone_number,omitempty"`
    Status      string `dynamodbav:"org_status"`   // 'status' es reservada en DynamoDB
    AdminUserID string `dynamodbav:"admin_user_id"`
    CreatedAt   string `dynamodbav:"created_at"`
}
```

---

## Flujo 1 — Login

| Grupo | `serial_number` | Validación extra |
|---|---|---|
| `ADMIN` | No | — |
| `CUSTOMER` | No | — |
| `CUSTOMER_OPERATOR` | **Sí** | Terminal debe existir en DynamoDB (GSI1) y no ser `INACTIVE` |

```
POST /api/v1/auth/login
        │
        ├─ NormalizeRUT + ValidateRUT
        ├─ Cognito InitiateAuth
        │     └─ Error → 401 / 404
        ├─ jwtClaims(accessToken) → sub + groups
        ├─ [si CUSTOMER_OPERATOR] Query GSI1 SERIAL#<serial> → validar terminal
        ├─ userIDBySub(sub) via GSI1 COGNITO#<sub>
        ├─ writeSessionActive(userID, TTL=24h)   ← best-effort, no bloquea login
        └─ 200 { access_token, id_token, refresh_token, expires_in }
```

---

## Flujo 2 — Registro simple

```
POST /api/v1/auth/register
        │
        ├─ NormalizeRUT + ValidateRUT
        ├─ [role == CUSTOMER] validar org_name presente
        ├─ AdminCreateUser + AdminSetUserPassword + AdminAddUserToGroup
        │
        ├─ [role == CUSTOMER]
        │     TransactWrite (3 ítems):
        │       Put ORGANIZATION#<org_id>/#METADATA
        │       Put USER#<user_id>/#METADATA  (con org_id = org_id)
        │       Put ORGANIZATION#<org_id>/USER#<user_id>
        │     → 201 { id, org_id }
        │
        ├─ [role == CUSTOMER_OPERATOR con org_id]
        │     TransactWrite (2 ítems):
        │       Put USER#<user_id>/#METADATA
        │       Put ORGANIZATION#<org_id>/OPERATOR#<user_id>
        │     → 201 { id }
        │
        └─ [DynamoDB falla] → AdminDeleteUser (rollback) → 500
```

---

## Flujo 3 — Registro batch (Excel)

El archivo llega como `multipart/form-data`, campo `file`.  
API Gateway HTTP v2 lo entrega en base64 (`IsBase64Encoded = true`).  
Todos los usuarios del batch reciben rol `CUSTOMER_OPERATOR`.

**Planilla esperada** (fila 1 = header, datos desde fila 2):

| A | B | C | D | E | F |
|---|---|---|---|---|---|
| rut | nombre | apellido | email | password | telefono |

Procesamiento en chunks de 25 → respeta rate limit de Cognito (~50 req/s).  
Errores parciales: si una fila falla, el resto continúa.

---

## Flujo 4 — Logout propio

```
POST /api/v1/auth/logout  [JWT requerido]
        │
        ├─ sub = JWT.Claims["sub"]
        ├─ userIDBySub(sub) via GSI1
        ├─ GlobalSignOut(accessToken del header Authorization)
        │     → invalida refresh_token en Cognito
        ├─ deleteSessionActive(userID)
        └─ 200 { message: "sesion_cerrada" }
```

> El `access_token` sigue siendo válido hasta su expiración natural (~1h).

---

## Flujo 5 — Cierre remoto de sesión (ADMIN)

```
DELETE /api/v1/auth/sessions/{user_id}  [JWT requerido, solo ADMIN]
        │
        ├─ Verificar cognito:groups contiene "ADMIN"
        ├─ userRUTByID(user_id) → GetItem USER#<id>/#METADATA → rut
        ├─ AdminUserGlobalSignOut(userPoolId, username=rut)
        │     → invalida todos los refresh_tokens del usuario
        ├─ deleteSessionActive(user_id)
        └─ 200 { message: "sesion_cerrada" }
```

Casos de uso: cliente solicita bloqueo de acceso, operador pierde el terminal,
revocación de emergencia por parte del ADMIN de Haulmer.

---

## Estructura de archivos

```
auth-handler/
├── README.md
├── go.mod / go.sum
├── docs/
│   └── openapi.yaml
├── main.go              ← entry point, inicializa Cognito + DynamoDB
├── handler.go           ← Handler struct, router por event.RouteKey, jsonResponse
├── login.go             ← HandleLogin, jwtClaims, validateTerminal
├── session.go           ← HandleLogout, HandleAdminCloseSession
├── register.go          ← HandleRegister, createCognitoUser
├── dynamo.go            ← UserItem, OrgItem, writeUserRecord, helpers de sesión
├── register_batch.go    ← HandleBatch: parsea Excel, chunking
└── rut/
    ├── normalize.go
    └── validate.go
```

---

## cURL de prueba

```bash
BASE="https://qnehzrs7g4.execute-api.us-east-1.amazonaws.com"
TOKEN="Bearer <access_token>"

# Login — ADMIN / CUSTOMER (web)
curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"rut":"12345678-5","password":"MiPass123!"}' | jq

# Login — CUSTOMER_OPERATOR (terminal)
curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"rut":"87654321-K","password":"OtraPass456!","serial_number":"TUU-2024-001"}' | jq

# Registrar CUSTOMER + organización (ADMIN llama este endpoint)
curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "rut": "87654321-K",
    "password": "OtraPass456!",
    "email": "admin@empresa.cl",
    "given_name": "María",
    "family_name": "López",
    "role": "CUSTOMER",
    "org_name": "Estacionamiento Central SpA",
    "org_rut": "76.543.210-K"
  }' | jq

# Registrar operador en una org existente
curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "rut": "12345678-5",
    "password": "MiPass123!",
    "email": "juan.perez@empresa.cl",
    "given_name": "Juan",
    "family_name": "Pérez",
    "role": "CUSTOMER_OPERATOR",
    "org_id": "c0c0c0c0-0000-0000-0000-000000000000"
  }' | jq

# Batch
curl -s -X POST $BASE/api/v1/auth/register/batch \
  -F "file=@/ruta/al/archivo.xlsx" | jq

# Logout propio
curl -s -X POST $BASE/api/v1/auth/logout \
  -H "Authorization: $TOKEN" | jq

# Cierre remoto de sesión (ADMIN)
curl -s -X DELETE $BASE/api/v1/auth/sessions/<user_id> \
  -H "Authorization: $TOKEN" | jq
```

---

## RUTs válidos para tests rápidos

| RUT | DV |
|-----|----|
| `12345678-5` | 5 |
| `11111111-1` | 1 |
| `98765432-1` | 1 |
