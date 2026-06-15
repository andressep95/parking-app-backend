# DynamoDB — Single-Table Design

Diseño de tabla única para **parking-app** siguiendo el patrón Single-Table de DynamoDB.

> **Principio base:** los patrones de acceso dictan el diseño. El esquema SQL de referencia describe las entidades y sus relaciones; este documento las adapta para lectura eficiente en DynamoDB sin JOINs ni scans.

---

## Tabla de contenidos

- [Entidades del dominio](#entidades-del-dominio)
- [Patrones de acceso](#patrones-de-acceso)
- [Diseño de la tabla](#diseño-de-la-tabla)
- [GSIs (Global Secondary Indexes)](#gsis)
- [Mapa de patrones → claves](#mapa-de-patrones--claves)
- [Ejemplos de ítems](#ejemplos-de-ítems)
- [Convenciones y reglas](#convenciones-y-reglas)

---

## Entidades del dominio

| Entidad | Descripción |
|---------|-------------|
| `User` | Admin (Haulmer), Customer (empresa), Customer Operator (cajero) |
| `Location` | Sucursal o estacionamiento de un Customer |
| `Terminal` | Dispositivo Android TUU físico en una Location |
| `UserSession` | Sesión de login — enforcement de sesión única |
| `Tariff` | Precio por tipo de vehículo para una Location |
| `Vehicle` | Registro de patente (se crea al primer ingreso) |
| `Shift` | Turno de un operador en un terminal |
| `ParkingSession` | Estadía completa de un vehículo (ingreso → salida → cobro) |
| `Transaction` | Pago procesado por TUU para una ParkingSession |
| `AuditLog` | Trazabilidad de acciones administrativas (inmutable) |

---

## Patrones de acceso

Definidos desde la perspectiva de las operaciones reales del sistema. La frecuencia orienta qué debe resolverse en la tabla principal vs. GSIs.

### Autenticación y usuarios

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP01 | Obtener usuario por `cognito_sub` | **Muy alta** | Cada llamada autenticada |
| AP02 | Obtener usuario por `email` | Media | Login web, búsqueda admin |
| AP03 | Obtener usuario por `id` | Alta | Resolución de FK en respuestas |
| AP04 | Listar operadores de un Customer | Media | Dashboard de gestión |
| AP05 | Verificar sesión activa de un usuario (sesión única) | **Muy alta** | Cada login |
| AP06 | Registrar / cerrar sesión de usuario | Alta | Login y logout |

### Ubicaciones y terminales

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP07 | Listar Locations de un Customer | Media | Panel admin Customer |
| AP08 | Obtener Location por `id` | Alta | Validación en operaciones |
| AP09 | Obtener Terminal por `serial_number` | **Muy alta** | Autenticación de dispositivo |
| AP10 | Listar Terminales de una Location | Media | Dashboard de sede |
| AP11 | Actualizar estado de Terminal (heartbeat) | Alta | Cada pocos minutos por dispositivo |

### Tarifas

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP12 | Obtener tarifa activa por `location_id` + `vehicle_type` | **Muy alta** | Cada ingreso de vehículo |
| AP13 | Listar todas las tarifas de una Location | Media | Configuración, display terminal |

### Estacionamiento (operaciones en tiempo real)

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP14 | Listar sesiones ACTIVAS de una Location | **Muy alta** | Dashboard en tiempo real |
| AP15 | Buscar sesión ACTIVA por patente | **Muy alta** | Cobro / salida de vehículo |
| AP16 | Crear sesión de estacionamiento (ingreso) | Alta | Por cada vehículo que entra |
| AP17 | Cerrar sesión de estacionamiento (salida + cobro) | Alta | Por cada vehículo que sale |
| AP18 | Obtener ParkingSession por `id` | Alta | Cobro, auditoría, reimpresión |

### Turnos (shifts)

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP19 | Obtener shift ACTIVO de un operador | **Muy alta** | Cada operación del terminal |
| AP20 | Abrir / cerrar shift de operador | Baja | Inicio y fin de turno |
| AP21 | Historial de shifts de un operador | Baja | Dashboard operador |
| AP22 | Listar ParkingSessions de un shift | Media | Cierre de turno |
| AP23 | Listar Transactions de un shift | Media | Cierre de turno, cuadre de caja |

### Reportes

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP24 | Sesiones por Location + rango de fechas | Media | Reportes históricos |
| AP25 | Transacciones por Location + rango de fechas | Media | Reportes de recaudación |

### Auditoría

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP26 | Audit log por entidad + `entity_id` | Baja | Trazabilidad admin |
| AP27 | Audit log por `user_id` + rango de fechas | Baja | Acciones de un usuario |

---

## Diseño de la tabla

**Nombre:** `{env}-parking-app` (ej: `dev-parking-app`)

**Claves primarias:**
- `PK` — Partition Key (String)
- `SK` — Sort Key (String)

**Atributos dispersos para GSIs:**
- `GSI1PK`, `GSI1SK` — Lookups por claves alternas únicas
- `GSI2PK`, `GSI2SK` — Consultas por rango de fecha sobre entidades secundarias

---

### Patrones PK / SK por entidad

#### User

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `USER#<id>` | `#METADATA` | Ítem principal del usuario | AP03 |
| `CUSTOMER#<customer_id>` | `OPERATOR#<user_id>` | Operadores de un Customer | AP04 |

**Atributos del ítem `USER#<id> / #METADATA`:**
```
id, cognito_sub, email, full_name, company_name, role,
status, customer_id, location_id, created_at, updated_at
GSI1PK = "COGNITO#<cognito_sub>"   → AP01
GSI2PK = "EMAIL#<email>"           → AP02
```

---

#### UserSession

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `USER#<user_id>` | `SESSION#ACTIVE` | Sesión activa (solo 1 por usuario) | AP05 |
| `USER#<user_id>` | `SESSION#<started_at>#<session_id>` | Historial de sesiones | — |

> La sesión activa ocupa el SK fijo `SESSION#ACTIVE`. Al cerrar sesión se reescribe con el SK histórico. Esto garantiza que `GetItem(PK=USER#x, SK=SESSION#ACTIVE)` es O(1) y detecta sesión duplicada antes de emitir tokens.

---

#### Location

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `LOCATION#<id>` | `#METADATA` | Ítem principal de la ubicación | AP08 |
| `CUSTOMER#<customer_id>` | `LOCATION#<location_id>` | Ubicaciones de un Customer | AP07 |

---

#### Terminal

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `TERMINAL#<id>` | `#METADATA` | Ítem principal del terminal | — |
| `LOCATION#<location_id>` | `TERMINAL#<terminal_id>` | Terminales de una Location | AP10 |

**Atributos del ítem `TERMINAL#<id> / #METADATA`:**
```
id, serial_number, model, customer_id, location_id,
status, active_operator_id, app_version, last_heartbeat
GSI1PK = "SERIAL#<serial_number>"  → AP09
```

---

#### Tariff

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `LOCATION#<location_id>` | `TARIFF#<vehicle_type>` | Tarifa activa por tipo | AP12, AP13 |

> Un ítem por tipo de vehículo por ubicación. Solo tarifas activas (`is_active=true`) viven aquí. Las históricas se archivan en `TARIFF_HISTORY#<location_id>` con SK `<vehicle_type>#<valid_from>`.

---

#### Shift

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `SHIFT#<id>` | `#METADATA` | Ítem principal del turno | AP22, AP23 |
| `OPERATOR#<operator_id>` | `SHIFT#ACTIVE` | Turno activo del operador (SK fijo) | AP19 |
| `OPERATOR#<operator_id>` | `SHIFT#<started_at>#<shift_id>` | Historial de turnos | AP21 |

> Mismo patrón que UserSession: SK fijo `SHIFT#ACTIVE` para O(1) en AP19 (el más frecuente del sistema operativo).

---

#### ParkingSession

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `PARKING#<id>` | `#METADATA` | Ítem principal de la sesión | AP18 |
| `LOCATION#<location_id>` | `PARKING#ACTIVE#<parking_id>` | Sesiones activas en una sede | AP14 |
| `LOCATION#<location_id>` | `PARKING#<YYYY-MM-DD>#<parking_id>` | Historial por sede + fecha | AP24 |
| `SHIFT#<shift_id>` | `PARKING#<entry_at>#<parking_id>` | Sesiones de un turno | AP22 |

**Atributos del ítem de sesión activa `LOCATION#<id> / PARKING#ACTIVE#<parking_id>`:**
```
parking_id, plate, vehicle_type, entry_at,
operator_id_in, terminal_id_in, tariff_snapshot
GSI1PK = "PLATE#<plate>"    → AP15 (buscar activa por patente)
GSI1SK = "ACTIVE"
```

> Al cerrar la sesión se elimina el ítem `PARKING#ACTIVE#<id>` y se escribe `PARKING#<YYYY-MM-DD>#<id>`. El GSI1 (PLATE) pierde el ítem al eliminarse automáticamente.

---

#### Transaction

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `TRANSACTION#<id>` | `#METADATA` | Ítem principal de la transacción | — |
| `PARKING#<session_id>` | `TRANSACTION#<transaction_id>` | Transacción de una sesión (1:1) | AP18 → join |
| `SHIFT#<shift_id>` | `TRANSACTION#<transaction_at>#<transaction_id>` | Transacciones de un turno | AP23 |

---

#### AuditLog

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `AUDIT#<entity>#<entity_id>` | `<occurred_at>#<audit_id>` | Log por entidad | AP26 |

**Atributos:**
```
audit_id, user_id, action, entity, entity_id,
details (JSON), ip_address, occurred_at
GSI2PK = "AUDIT_USER#<user_id>"
GSI2SK = "<occurred_at>"           → AP27
```

---

## GSIs

### GSI1 — Lookups por clave alterna

**Partition key:** `GSI1PK` | **Sort key:** `GSI1SK`

| GSI1PK | GSI1SK | Entidad | AP |
|--------|--------|---------|-----|
| `COGNITO#<cognito_sub>` | `USER#<id>` | User | AP01 |
| `EMAIL#<email>` | `USER#<id>` | User | AP02 |
| `SERIAL#<serial_number>` | `TERMINAL#<id>` | Terminal | AP09 |
| `PLATE#<plate>` | `ACTIVE` | ParkingSession activa | AP15 |

> GSI1 es **sparse**: solo los ítems con `GSI1PK` definido aparecen. Las ParkingSessions solo tienen `GSI1PK=PLATE#<plate>` mientras están ACTIVAS — al cerrar se elimina el ítem de Location y el GSI pierde la entrada automáticamente.

### GSI2 — Consultas de auditoría por usuario

**Partition key:** `GSI2PK` | **Sort key:** `GSI2SK`

| GSI2PK | GSI2SK | Entidad | AP |
|--------|--------|---------|-----|
| `AUDIT_USER#<user_id>` | `<occurred_at>` | AuditLog | AP27 |

---

## Mapa de patrones → claves

| AP | Operación DynamoDB | PK | SK / condición |
|----|-------------------|----|---------------|
| AP01 | `Query GSI1` | `COGNITO#<sub>` | — |
| AP02 | `Query GSI1` | `EMAIL#<email>` | — |
| AP03 | `GetItem` | `USER#<id>` | `#METADATA` |
| AP04 | `Query` | `CUSTOMER#<id>` | `begins_with(SK, "OPERATOR#")` |
| AP05 | `GetItem` | `USER#<id>` | `SESSION#ACTIVE` |
| AP06 | `PutItem / UpdateItem` | `USER#<id>` | `SESSION#ACTIVE` |
| AP07 | `Query` | `CUSTOMER#<id>` | `begins_with(SK, "LOCATION#")` |
| AP08 | `GetItem` | `LOCATION#<id>` | `#METADATA` |
| AP09 | `Query GSI1` | `SERIAL#<serial>` | — |
| AP10 | `Query` | `LOCATION#<id>` | `begins_with(SK, "TERMINAL#")` |
| AP11 | `UpdateItem` | `TERMINAL#<id>` | `#METADATA` |
| AP12 | `GetItem` | `LOCATION#<id>` | `TARIFF#<vehicle_type>` |
| AP13 | `Query` | `LOCATION#<id>` | `begins_with(SK, "TARIFF#")` |
| AP14 | `Query` | `LOCATION#<id>` | `begins_with(SK, "PARKING#ACTIVE#")` |
| AP15 | `Query GSI1` | `PLATE#<plate>` | `SK = "ACTIVE"` |
| AP16 | `TransactWrite` | `PARKING#<id>` + `LOCATION#<id>` | Escribe `#METADATA` + `PARKING#ACTIVE#<id>` + `SHIFT#<id>/PARKING#...` |
| AP17 | `TransactWrite` | `LOCATION#<id>` + `PARKING#<id>` | Elimina `PARKING#ACTIVE#<id>`, escribe `PARKING#<date>#<id>` + Transaction |
| AP18 | `GetItem` | `PARKING#<id>` | `#METADATA` |
| AP19 | `GetItem` | `OPERATOR#<id>` | `SHIFT#ACTIVE` |
| AP20 | `TransactWrite` | `OPERATOR#<id>` + `SHIFT#<id>` | Escribe/reescribe `SHIFT#ACTIVE` y `#METADATA` |
| AP21 | `Query` | `OPERATOR#<id>` | `begins_with(SK, "SHIFT#")` |
| AP22 | `Query` | `SHIFT#<id>` | `begins_with(SK, "PARKING#")` |
| AP23 | `Query` | `SHIFT#<id>` | `begins_with(SK, "TRANSACTION#")` |
| AP24 | `Query` | `LOCATION#<id>` | `between(SK, "PARKING#<fecha_ini>", "PARKING#<fecha_fin>")` |
| AP25 | `Query` | `SHIFT#<id>` | `begins_with(SK, "TRANSACTION#")` por cada shift del rango |
| AP26 | `Query` | `AUDIT#<entity>#<entity_id>` | range en SK por fechas |
| AP27 | `Query GSI2` | `AUDIT_USER#<user_id>` | range en GSI2SK por fechas |

---

## Ejemplos de ítems

### Usuario (Customer Operator)

```json
{
  "PK":          "USER#a1b2c3d4-...",
  "SK":          "#METADATA",
  "id":          "a1b2c3d4-...",
  "cognito_sub": "us-east-1_abc|xyz",
  "email":       "juan.perez@estacionamiento.cl",
  "full_name":   "Juan Pérez",
  "role":        "CUSTOMER_OPERATOR",
  "status":      "ACTIVE",
  "customer_id": "c0c0c0c0-...",
  "location_id": "l1l1l1l1-...",
  "created_at":  "2026-06-14T10:00:00Z",
  "GSI1PK":      "COGNITO#us-east-1_abc|xyz",
  "GSI1SK":      "USER#a1b2c3d4-...",
  "GSI2PK":      "EMAIL#juan.perez@estacionamiento.cl",
  "GSI2SK":      "USER#a1b2c3d4-..."
}
```

### Sesión activa de usuario (enforcement sesión única)

```json
{
  "PK":         "USER#a1b2c3d4-...",
  "SK":         "SESSION#ACTIVE",
  "session_id": "s1s1s1s1-...",
  "terminal_id":"t1t1t1t1-...",
  "ip_address": "192.168.1.10",
  "started_at": "2026-06-14T08:30:00Z",
  "ttl":        1750000000
}
```

> `ttl` — DynamoDB TTL elimina la sesión automáticamente si el cliente no hace logout explícito (previene sesiones huérfanas).

### Tarifa activa

```json
{
  "PK":             "LOCATION#l1l1l1l1-...",
  "SK":             "TARIFF#CAR",
  "tariff_id":      "tf1tf1tf1-...",
  "name":           "Tarifa Normal Auto",
  "price_per_hour": "1500.00",
  "minimum_charge": "500.00",
  "grace_minutes":  10,
  "valid_from":     "2026-01-01T00:00:00Z"
}
```

### Sesión de estacionamiento activa (en Location)

```json
{
  "PK":             "LOCATION#l1l1l1l1-...",
  "SK":             "PARKING#ACTIVE#ps1ps1ps1-...",
  "parking_id":     "ps1ps1ps1-...",
  "plate":          "BCDF12",
  "vehicle_type":   "CAR",
  "entry_at":       "2026-06-14T09:15:00Z",
  "operator_id_in": "a1b2c3d4-...",
  "terminal_id_in": "t1t1t1t1-...",
  "tariff_snapshot": {
    "price_per_hour": "1500.00",
    "grace_minutes": 10
  },
  "GSI1PK": "PLATE#BCDF12",
  "GSI1SK": "ACTIVE"
}
```

### Sesión de estacionamiento completada (en Location, historial)

```json
{
  "PK":               "LOCATION#l1l1l1l1-...",
  "SK":               "PARKING#2026-06-14#ps1ps1ps1-...",
  "parking_id":       "ps1ps1ps1-...",
  "plate":            "BCDF12",
  "vehicle_type":     "CAR",
  "entry_at":         "2026-06-14T09:15:00Z",
  "exit_at":          "2026-06-14T11:45:00Z",
  "duration_minutes": 150,
  "calculated_amount":"3500.00",
  "status":           "COMPLETED"
}
```

### Shift activo de operador

```json
{
  "PK":          "OPERATOR#a1b2c3d4-...",
  "SK":          "SHIFT#ACTIVE",
  "shift_id":    "sh1sh1sh1-...",
  "terminal_id": "t1t1t1t1-...",
  "location_id": "l1l1l1l1-...",
  "started_at":  "2026-06-14T08:00:00Z",
  "opening_cash":"50000.00"
}
```

### Audit log

```json
{
  "PK":         "AUDIT#tariff#tf1tf1tf1-...",
  "SK":         "2026-06-14T10:30:00Z#au1au1au1-...",
  "audit_id":   "au1au1au1-...",
  "user_id":    "c0c0c0c0-...",
  "action":     "UPDATE_TARIFF",
  "entity":     "tariff",
  "entity_id":  "tf1tf1tf1-...",
  "details":    { "before": { "price_per_hour": "1000.00" }, "after": { "price_per_hour": "1500.00" } },
  "occurred_at":"2026-06-14T10:30:00Z",
  "GSI2PK":    "AUDIT_USER#c0c0c0c0-...",
  "GSI2SK":    "2026-06-14T10:30:00Z"
}
```

---

## Convenciones y reglas

### Formato de claves
- Todos los timestamps en SK usan formato ISO-8601 UTC (`YYYY-MM-DDTHH:MM:SSZ`) para que el ordenamiento lexicográfico de DynamoDB coincida con el cronológico.
- Los UUIDs no llevan separadores adicionales en las claves.
- Los valores de tipo enum se escriben en MAYÚSCULAS en las claves (`TARIFF#CAR`, no `tariff#car`).

### Patrones SK fijos para estado activo
`SESSION#ACTIVE` y `SHIFT#ACTIVE` usan SK fijo deliberadamente: permite `GetItem` O(1) para los patrones más frecuentes del sistema. Al finalizar el estado, el ítem se **reescribe** con SK histórico (no se actualiza el SK, se elimina y se crea uno nuevo).

### Transacciones DynamoDB (`TransactWrite`)
Las operaciones que afectan múltiples ítems atómicamente (AP16, AP17, AP20) usan `TransactWrite` con hasta 100 operaciones. Garantiza consistencia sin transacciones distribuidas.

### TTL
`UserSession` incluye atributo `ttl` (Unix epoch). DynamoDB elimina sesiones expiradas automáticamente sin necesidad de un job de limpieza.

### Snapshot de tarifa en ParkingSession
`tariff_snapshot` almacena el precio vigente al momento del ingreso. Protege contra cambios de tarifa durante una estadía activa y elimina un JOIN al momento del cobro.

### Capacidad
- **Billing:** On-demand (PAY_PER_REQUEST) en dev; evaluar provisioned + auto-scaling en prod según carga real.
- **Proyecciones GSI:** `ALL` en GSI1 (ítems pequeños, frecuencia alta); `KEYS_ONLY` en GSI2 (solo auditoría).
