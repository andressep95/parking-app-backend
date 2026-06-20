# organization-handler

Lambda Go para CRUD de organizaciones (empresas cliente) del sistema de parking.

## Arquitectura

```
API Gateway (JWT auth)
        │
        ▼
organization-handler (Lambda arm64 / provided.al2023)
        │
        ▼
  DynamoDB (single-table)
  ORGANIZATION#<id> / #METADATA
```

Las organizaciones son entidades **DynamoDB puras** — no tienen entidad Cognito propia. La autenticación la gestionan los usuarios CUSTOMER y CUSTOMER_OPERATOR de la organización.

## Variables de entorno

| Variable             | Descripción                          |
|----------------------|--------------------------------------|
| `DYNAMODB_TABLE_NAME`| Nombre de la tabla DynamoDB principal |

## Permisos IAM requeridos

- `dynamodb:GetItem`
- `dynamodb:UpdateItem`
- `dynamodb:DeleteItem`
- `dynamodb:Query` (GSI1 para verificar org del caller)
- `dynamodb:Scan` (listar todas las orgs — ADMIN)
- `dynamodb:TransactWriteItems` (delete atómico)

## Endpoints

| Método | Ruta                                     | ADMIN | CUSTOMER |
|--------|------------------------------------------|:-----:|:--------:|
| GET    | `/api/v1/organizations`                  | ✓     | —        |
| GET    | `/api/v1/organizations/{id}`             | ✓     | propia   |
| PUT    | `/api/v1/organizations/{id}`             | ✓     | propia   |
| POST   | `/api/v1/organizations/{id}/activate`    | ✓     | —        |
| POST   | `/api/v1/organizations/{id}/deactivate`  | ✓     | —        |
| DELETE | `/api/v1/organizations/{id}`             | ✓     | —        |

## Modelo DynamoDB

### OrgItem (`ORGANIZATION#<id>` / `#METADATA`)

```go
type OrgItem struct {
    PK          string  // ORGANIZATION#<uuid>
    SK          string  // #METADATA
    ID          string  // uuid
    OrgName     string  // org_name   — 'name' es reservada en DynamoDB
    RutEmpresa  string  // rut_empresa
    Email       string  // org_email  — 'email' es reservada en DynamoDB
    Phone       string  // phone_number
    Status      string  // org_status — 'status' es reservada en DynamoDB
    AdminUserID string  // admin_user_id — UUID del usuario CUSTOMER admin
    CreatedAt   string  // RFC3339
}
```

### Items relacionados

```
ORGANIZATION#<id> / #METADATA           ← metadata de la org
ORGANIZATION#<id> / USER#<admin_id>     ← link al usuario CUSTOMER admin
ORGANIZATION#<id> / OPERATOR#<uid>      ← links a operadores (creados en auth-handler)
ORGANIZATION#<id> / LOCATION#<lid>      ← links a sedes (creados en location-handler)
```

## Verificación de pertenencia (CUSTOMER)

Para GET y PUT, un CUSTOMER solo puede operar sobre su propia organización. Como `org_id` no es un claim JWT estándar de Cognito, la verificación se hace vía GSI1:

```
GSI1PK = COGNITO#<caller_sub>  →  USER#<uid>  →  org_id
```

Si `callerOrgID != pathParam id` → 403.

## Flujos

### GET /api/v1/organizations

```
ADMIN → Scan DynamoDB
        FilterExpression: SK = "#METADATA" AND begins_with(PK, "ORGANIZATION#")
        Limit: 100
        → []OrgResponse
```

### GET /api/v1/organizations/{id}

```
ADMIN  → GetItem ORGANIZATION#<id>/#METADATA → OrgResponse
CUSTOMER → callerOrgID(sub) via GSI1 → verificar id == org_id → GetItem
```

### PUT /api/v1/organizations/{id}

```
Verificar pertenencia (igual que GET)
UpdateItem con expression builder (org_name, rut_empresa, org_email, phone_number)
ConditionExpression: attribute_exists(PK)
```

### DELETE /api/v1/organizations/{id}

```
ADMIN → GetItem (para obtener admin_user_id)
      → TransactWriteItems:
          Delete ORGANIZATION#<id>/#METADATA
          Delete ORGANIZATION#<id>/USER#<admin_id>
```

> Los links `OPERATOR#` y `LOCATION#` bajo la organización no se eliminan en cascada — requieren limpieza explícita (futuro: batch cleanup vía Step Functions o Lambda auxiliar).

## Build

```bash
GOARCH=arm64 GOOS=linux go build -o bootstrap .
zip organization-handler.zip bootstrap
```

## Ejemplos cURL

```bash
BASE="https://<api-id>.execute-api.us-east-1.amazonaws.com"
TOKEN="Bearer <cognito-access-token>"

# Listar organizaciones (ADMIN)
curl -H "Authorization: $TOKEN" "$BASE/api/v1/organizations"

# Obtener organización
curl -H "Authorization: $TOKEN" "$BASE/api/v1/organizations/<org-id>"

# Actualizar nombre
curl -X PUT -H "Authorization: $TOKEN" -H "Content-Type: application/json" \
  -d '{"org_name": "Nuevo Nombre SA"}' \
  "$BASE/api/v1/organizations/<org-id>"

# Desactivar (ADMIN)
curl -X POST -H "Authorization: $TOKEN" \
  "$BASE/api/v1/organizations/<org-id>/deactivate"

# Eliminar (ADMIN)
curl -X DELETE -H "Authorization: $TOKEN" \
  "$BASE/api/v1/organizations/<org-id>"
```
