# DynamoDB — Single-Table Design

Diseño de tabla única para **parking-app** siguiendo el patrón Single-Table de DynamoDB.

> **Principio base (AWS oficial):** los patrones de acceso dictan el diseño — no al revés. No diseñes el esquema hasta tener claros los access patterns. El esquema evoluciona **añadiendo GSIs** o atributos, no reestructurando PKs existentes.

---

## Tabla de contenidos

- [Principios de diseño DynamoDB](#principios-de-diseño-dynamodb)
- [Límites y cuotas críticas](#límites-y-cuotas-críticas)
- [Entidades del dominio](#entidades-del-dominio)
- [Patrones de acceso](#patrones-de-acceso)
- [Diseño de la tabla](#diseño-de-la-tabla)
- [GSIs (Global Secondary Indexes)](#gsis)
- [Mapa de patrones → claves](#mapa-de-patrones--claves)
- [Ejemplos de ítems](#ejemplos-de-ítems)
- [Estrategia de evolución del esquema](#estrategia-de-evolución-del-esquema)
- [Referencia TransactWrite](#referencia-transactwrite)
- [Convenciones y reglas](#convenciones-y-reglas)
- [Consideraciones de escala y costo](#consideraciones-de-escala-y-costo)

---

## Principios de diseño DynamoDB

Extraídos de la documentación oficial de AWS Best Practices.

### Mentalidad NoSQL vs RDBMS

| RDBMS | DynamoDB |
|---|---|
| Diseñas para flexibilidad, normalizas primero | Diseñas para los access patterns específicos |
| Queries flexibles, costosas a escala | Queries limitadas pero muy baratas y rápidas |
| Schema uniform por tabla | Una tabla puede tener ítems de distintas entidades |
| JOINs en query time | Pre-computes las relaciones en write time (denormalización) |
| ALTER TABLE para evolucionar | Añades atributos/GSIs sin migración de schema |

### Las tres propiedades antes de diseñar (AWS)

1. **Data size** — ¿Cuánta data se almacena y se consulta a la vez? Afecta cómo particionar.
2. **Data shape** — La forma de la data en la tabla debe coincidir exactamente con lo que se consulta. No hay reshaping en query time.
3. **Data velocity** — ¿Cuál es el pico de carga por access pattern? Determina cómo distribuir la data para no exceder I/O por partición.

### Principios de diseño (AWS oficial)

- **Mantén la data relacionada junta** — "locality of reference" es el factor clave de performance en NoSQL. Un solo `GetItem` o `Query` debe resolver el caso de uso sin múltiples round trips.
- **Usa sort order** — Los ítems relacionados agrupados y ordenados por SK permiten range queries eficientes con `begins_with`, `between`, `>`, `<`.
- **Distribuye los queries** — Evita hot partitions. Las PKs deben tener alta cardinalidad y distribución uniforme.
- **Usa GSIs** — Para queries que la tabla principal no puede resolver. Mantén el número al mínimo.
- **Mínimo de tablas posible** — Una tabla por aplicación en la mayoría de los casos. Excepciones: datos de time series de alto volumen o datasets con access patterns completamente distintos.

### Diseño de Partition Key (PK) — Reglas de cardinalidad

La PK determina la distribución en particiones físicas. Cada partición soporta hasta **3.000 RCU/s y 1.000 WCU/s**.

| Ejemplo de PK | Uniformidad |
|---|---|
| `USER#<uuid>` — muchos usuarios | ✅ Buena |
| `LOCATION#<uuid>` — muchas sedes | ✅ Buena |
| `STATUS#<activo/inactivo>` — pocos valores posibles | ❌ Mala (hot partition) |
| `PARKING#<fecha_redondeada>` — toda escritura del día va a la misma partición | ❌ Mala (hot partition) |

> **Anti-pattern crítico:** usar una fecha como PK hace que todos los ítems del día caigan en la misma partición. Usar `LOCATION#<uuid>` como PK y la fecha en SK resuelve esto.

### Diseño de Sort Key (SK) — Poder de range queries

Sort keys compuestos permiten queries jerárquicas a cualquier nivel:

```
# Ejemplo de SK jerárquico (geográfico)
[pais]#[region]#[ciudad]#[sede]

# Permite:
begins_with(SK, "CL")           → todo Chile
begins_with(SK, "CL#RM")        → solo Región Metropolitana
begins_with(SK, "CL#RM#SGO")    → solo Santiago
```

Para timestamps: ISO-8601 UTC (`YYYY-MM-DDTHH:MM:SSZ`) — el orden lexicográfico coincide con el cronológico, lo que habilita `between` y range queries naturales.

### Sparse Indexes — Feature, no bug

Un GSI solo incluye ítems que **tienen definido el atributo de la partition key del GSI**. Si el atributo no existe en el ítem, el ítem no aparece en el GSI. Esto es el patrón **sparse index**:

- Se usa para indexar subconjuntos de ítems (ej: solo sesiones ACTIVAS, no las completadas).
- Al eliminar el atributo GSI del ítem (o eliminar el ítem), desaparece del GSI automáticamente.
- Permite provisionar el GSI con menor throughput que la tabla base.

### GSI Overloading — Un GSI, múltiples entidades

Un GSI puede indexar ítems de distintas entidades usando prefijos en la PK del GSI:

```
GSI1PK = "COGNITO#<sub>"   → busca Users por cognito_sub
GSI1PK = "SERIAL#<num>"    → busca Terminals por serial_number
GSI1PK = "PLATE#<patente>" → busca ParkingSessions activas por patente
```

Los tres coexisten en el mismo GSI1. Los prefijos evitan colisiones. Esta técnica permite indexar muchos más campos que el límite de 20 GSIs.

> **Nota importante:** un mismo ítem puede aparecer en **múltiples GSIs** a la vez. Un `User` con `GSI1PK = "COGNITO#..."` y `GSI2PK = "EMAIL#..."` aparece en ambos GSIs simultáneamente.

---

## Límites y cuotas críticas

| Límite | Valor |
|---|---|
| Tamaño máximo de ítem | **400 KB** |
| GSIs por tabla | **20** (cuota por defecto) |
| LSIs por tabla | **5** (no eliminables una vez creados) |
| Item collection size (LSI) | **10 GB** por partition key value |
| Throughput por partición | **3.000 RCU/s · 1.000 WCU/s** |
| `TransactWriteItems` — max ítems | **100 ítems** |
| `TransactWriteItems` — max tamaño total | **4 MB** |
| `TransactWriteItems` — costo | **2× WCU** por ítem (prepare + commit) |
| `TransactGetItems` — costo | **2× RCU** por ítem |
| Tablas por cuenta | **10.000** |
| TTL — latencia de eliminación | Hasta **48 h** tras expiración (eventual) |
| Nombres de atributos | Se almacenan **por ítem** — nombres cortos ahorran storage |

---

## Entidades del dominio

| Entidad | Descripción |
|---------|-------------|
| `User` | Admin (Haulmer), Customer (empresa), Customer Operator (cajero) |
| `Location` | Sucursal o estacionamiento de un Customer |
| `Terminal` | Dispositivo Android TUU físico en una Location |
| `UserSession` | Sesión de login — enforcement de sesión única |
| `Tariff` | Precio por tipo de vehículo para una Location |
| `Vehicle` | Registro de patente conocida (se crea al primer ingreso) |
| `Shift` | Turno de un operador en un terminal |
| `ParkingSession` | Estadía completa de un vehículo (ingreso → salida → cobro) |
| `Transaction` | Pago procesado por TUU para una ParkingSession |
| `AuditLog` | Trazabilidad de acciones administrativas (inmutable) |

---

## Patrones de acceso

Definidos desde la perspectiva de las operaciones reales del sistema.

### Autenticación y usuarios

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP01 | Obtener usuario por `cognito_sub` | **Muy alta** | Cada llamada autenticada — hot path |
| AP02 | Obtener usuario por `email` | Media | Login web, búsqueda admin |
| AP03 | Obtener usuario por `id` | Alta | Resolución interna |
| AP04 | Listar operadores de un Customer | Media | Dashboard admin |
| AP05 | Verificar sesión activa de un usuario (sesión única) | **Muy alta** | Cada login |
| AP06 | Registrar / cerrar sesión de usuario | Alta | Login y logout |

### Ubicaciones y terminales

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP07 | Listar Locations de un Customer | Media | Panel admin Customer |
| AP08 | Obtener Location por `id` | Alta | Validación en operaciones |
| AP09 | Obtener Terminal por `serial_number` | **Muy alta** | Autenticación de dispositivo — hot path |
| AP10 | Listar Terminales de una Location | Media | Dashboard de sede |
| AP11 | Actualizar estado de Terminal (heartbeat) | Alta | Cada pocos minutos por dispositivo |

### Tarifas

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP12 | Obtener tarifa activa por `location_id` + `vehicle_type` | **Muy alta** | Cada ingreso de vehículo — hot path |
| AP13 | Listar todas las tarifas de una Location | Media | Configuración, display terminal |

### Vehículos

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP14 | Obtener vehículo por patente | Alta | Auto-fill de `vehicle_type` al ingresar |

### Estacionamiento (operaciones en tiempo real)

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP15 | Listar sesiones ACTIVAS de una Location | **Muy alta** | Dashboard en tiempo real |
| AP16 | Buscar sesión ACTIVA por patente | **Muy alta** | Cobro / salida de vehículo — hot path |
| AP17 | Crear sesión de estacionamiento (ingreso) | Alta | Por cada vehículo que entra |
| AP18 | Cerrar sesión de estacionamiento (salida + cobro) | Alta | Por cada vehículo que sale |
| AP19 | Obtener ParkingSession por `id` | Alta | Cobro, auditoría, reimpresión |

### Turnos (shifts)

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP20 | Obtener shift ACTIVO de un operador | **Muy alta** | Cada operación del terminal — hot path |
| AP21 | Abrir / cerrar shift de operador | Baja | Inicio y fin de turno |
| AP22 | Historial de shifts de un operador | Baja | Dashboard operador |
| AP23 | Listar ParkingSessions de un shift | Media | Cierre de turno |
| AP24 | Listar Transactions de un shift | Media | Cierre de turno, cuadre de caja |

### Reportes

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP25 | Sesiones por Location + rango de fechas | Media | Reportes históricos |
| AP26 | Transacciones por Location + rango de fechas | Media | Reportes de recaudación — ver nota ⚠️ |

### Auditoría

| # | Patrón | Frecuencia | Notas |
|---|--------|-----------|-------|
| AP27 | Audit log por entidad + `entity_id` | Baja | Trazabilidad admin |
| AP28 | Audit log por `user_id` + rango de fechas | Baja | Acciones de un usuario |

> ⚠️ **AP26 — Operación multi-step (N+1):** No existe una query directa de transacciones por Location + fecha sin pasar por shifts. El flujo es: (1) Query shifts de la Location en el rango de fechas → obtiene N shift_ids; (2) Query transacciones por cada shift_id. Es aceptable para reportes admin ocasionales. Si esto se vuelve frecuente, se añade un GSI dedicado.

---

## Diseño de la tabla

**Nombre:** `{env}-parking-app` (ej: `dev-parking-app`)

**Claves primarias:**
- `PK` — Partition Key (String)
- `SK` — Sort Key (String)

**Atributos dispersos para GSIs:**
- `GSI1PK`, `GSI1SK` — Lookups de alta frecuencia por claves alternas únicas (sparse)
- `GSI2PK`, `GSI2SK` — Lookups por email (Users) y auditoría por usuario (AuditLog)

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

GSI1PK = "COGNITO#<cognito_sub>"     GSI1SK = "USER#<id>"    → AP01
GSI2PK = "EMAIL#<email>"             GSI2SK = "USER#<id>"    → AP02
```

> **Nota GSI:** el ítem User aparece en **ambos** GSIs simultáneamente. GSI1 para el lookup por `cognito_sub` (hot path, frecuencia muy alta), GSI2 para lookup por `email` (frecuencia media).

---

#### UserSession

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `USER#<user_id>` | `SESSION#ACTIVE` | Sesión activa — SK fijo, máx 1 por usuario | AP05, AP06 |
| `USER#<user_id>` | `SESSION#<started_at>#<session_id>` | Historial de sesiones (archivado) | — |

> **SK fijo `SESSION#ACTIVE`:** garantiza `GetItem` O(1) para detectar sesión duplicada antes de emitir tokens. Al cerrar sesión, el ítem se **elimina** y se escribe uno nuevo con SK histórico (no se puede actualizar un SK, se crea un ítem nuevo).
>
> **TTL:** el atributo `ttl` (Unix epoch) hace que DynamoDB elimine sesiones huérfanas automáticamente. La eliminación por TTL puede tardar hasta 48h — esto es eventual y aceptable para sesiones.

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
| `TERMINAL#<id>` | `#METADATA` | Ítem principal del terminal | AP11 |
| `LOCATION#<location_id>` | `TERMINAL#<terminal_id>` | Terminales de una Location | AP10 |

**Atributos del ítem `TERMINAL#<id> / #METADATA`:**
```
id, serial_number, model, customer_id, location_id,
status, active_operator_id, app_version, last_heartbeat

GSI1PK = "SERIAL#<serial_number>"    GSI1SK = "TERMINAL#<id>"    → AP09
```

---

#### Tariff

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `LOCATION#<location_id>` | `TARIFF#<vehicle_type>` | Tarifa activa por tipo | AP12, AP13 |
| `TARIFF_HISTORY#<location_id>` | `<vehicle_type>#<valid_from>` | Historial de tarifas archivadas | — |

> Un ítem por tipo de vehículo por ubicación. Solo tarifas activas viven bajo `LOCATION#`. Los cambios de tarifa se archivan en `TARIFF_HISTORY#` antes de sobrescribir el ítem activo.

---

#### Vehicle

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `VEHICLE#<plate>` | `#METADATA` | Registro de patente conocida | AP14 |

**Atributos del ítem `VEHICLE#<plate> / #METADATA`:**
```
plate, vehicle_type, first_seen_at, last_seen_at, total_sessions
```

> La patente es directamente la PK — `GetItem` O(1) sin GSI. Se crea como parte del `TransactWrite` de AP17 (ingreso) usando `ConditionExpression: attribute_not_exists(PK)` para no sobrescribir si ya existe, y `UpdateItem` para actualizar `last_seen_at` + `total_sessions` en visitas posteriores.

---

#### Shift

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `SHIFT#<id>` | `#METADATA` | Ítem principal del turno | AP23, AP24 |
| `OPERATOR#<operator_id>` | `SHIFT#ACTIVE` | Turno activo del operador — SK fijo | AP20 |
| `OPERATOR#<operator_id>` | `SHIFT#<started_at>#<shift_id>` | Historial de turnos | AP22 |

> Mismo patrón que UserSession: SK fijo `SHIFT#ACTIVE` para O(1) en AP20 (el más frecuente del sistema operativo). Al cerrar el turno, se elimina `SHIFT#ACTIVE` y se escribe el ítem histórico.

---

#### ParkingSession

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `PARKING#<id>` | `#METADATA` | Ítem principal de la sesión | AP19 |
| `LOCATION#<location_id>` | `PARKING#ACTIVE#<parking_id>` | Sesiones activas en una sede | AP15 |
| `LOCATION#<location_id>` | `PARKING#<YYYY-MM-DD>#<parking_id>` | Historial por sede + fecha | AP25 |
| `SHIFT#<shift_id>` | `PARKING#<entry_at>#<parking_id>` | Sesiones de un turno | AP23 |

**Atributos del ítem activo `LOCATION#<id> / PARKING#ACTIVE#<parking_id>`:**
```
parking_id, plate, vehicle_type, entry_at,
operator_id_in, terminal_id_in, tariff_snapshot

GSI1PK = "PLATE#<plate>"    GSI1SK = "ACTIVE"    → AP16
```

> **Sparse GSI1 para PLATE:** este ítem es el único que tiene `GSI1PK = "PLATE#..."`. Al cerrar la sesión, este ítem se **elimina** (se reemplaza por el ítem histórico que NO tiene GSI1PK), por lo que el GSI pierde la entrada automáticamente — sin necesidad de limpieza manual.
>
> **Separación ACTIVE vs histórico:** `PARKING#ACTIVE#` ordena antes que `PARKING#<fecha>#` porque `A` (ASCII 65) > `0-9` (ASCII 48-57). Un `between("PARKING#2026-01-01", "PARKING#2026-12-31")` en AP25 **nunca** incluirá ítems ACTIVE, garantizando separación limpia.

---

#### Transaction

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `TRANSACTION#<id>` | `#METADATA` | Ítem principal de la transacción | — |
| `PARKING#<session_id>` | `TRANSACTION#<transaction_id>` | Transacción de una sesión (1:1) | AP19 (fetch encadenado) |
| `SHIFT#<shift_id>` | `TRANSACTION#<transaction_at>#<transaction_id>` | Transacciones de un turno | AP24 |

---

#### AuditLog

| PK | SK | Descripción | AP |
|----|-----|-------------|-----|
| `AUDIT#<entity>#<entity_id>` | `<occurred_at>#<audit_id>` | Log por entidad + rango temporal | AP27 |

**Atributos:**
```
audit_id, user_id, action, entity, entity_id,
details (JSON), ip_address, occurred_at

GSI2PK = "AUDIT_USER#<user_id>"    GSI2SK = "<occurred_at>"    → AP28
```

---

## GSIs

### GSI1 — Lookups de alta frecuencia por clave alterna

**Partition key:** `GSI1PK` | **Sort key:** `GSI1SK`
**Proyección:** `ALL` (ítems pequeños, frecuencia muy alta, evita second fetch)

| GSI1PK | GSI1SK | Entidad | AP |
|--------|--------|---------|-----|
| `COGNITO#<cognito_sub>` | `USER#<id>` | User | AP01 |
| `SERIAL#<serial_number>` | `TERMINAL#<id>` | Terminal | AP09 |
| `PLATE#<plate>` | `ACTIVE` | ParkingSession activa | AP16 |

> **Sparse:** solo los ítems con `GSI1PK` definido aparecen. ParkingSessions solo tienen `GSI1PK` mientras están ACTIVAS. Terminals y Users siempre tienen `GSI1PK`.
>
> **Por qué ALL:** los ítems son pequeños (< 1 KB), la frecuencia es muy alta, y proyectar ALL elimina el second `GetItem`. A escala baja el costo de storage es despreciable; a escala alta, el ahorro de latencia justifica el costo.

---

### GSI2 — Lookups por email y auditoría por usuario

**Partition key:** `GSI2PK` | **Sort key:** `GSI2SK`
**Proyección:** `KEYS_ONLY` (frecuencia media/baja, se acepta second fetch para obtener datos completos)

| GSI2PK | GSI2SK | Entidad | AP |
|--------|--------|---------|-----|
| `EMAIL#<email>` | `USER#<id>` | User | AP02 |
| `AUDIT_USER#<user_id>` | `<occurred_at>` | AuditLog | AP28 |

> **Overloading de GSI2:** Users y AuditLogs comparten el mismo GSI2 mediante prefijos distintos (`EMAIL#` vs `AUDIT_USER#`). No hay colisión porque los prefijos son únicos y los queries siempre especifican el prefijo completo.
>
> **KEYS_ONLY + second fetch:** para AP02, la query devuelve `GSI2SK = "USER#<id>"` — luego un `GetItem(PK="USER#<id>", SK="#METADATA")` trae el usuario completo. Para AP28, se hace `BatchGetItem` con los `PK/SK` devueltos. Aceptable dado que ambos patterns son de baja/media frecuencia.

---

## Mapa de patrones → claves

| AP | Operación DynamoDB | PK | SK / condición |
|----|-------------------|----|---------------|
| AP01 | `Query GSI1` | `COGNITO#<sub>` | — |
| AP02 | `Query GSI2` | `EMAIL#<email>` | — |
| AP03 | `GetItem` | `USER#<id>` | `#METADATA` |
| AP04 | `Query` | `CUSTOMER#<id>` | `begins_with(SK, "OPERATOR#")` |
| AP05 | `GetItem` | `USER#<id>` | `SESSION#ACTIVE` |
| AP06 | `PutItem / DeleteItem` | `USER#<id>` | `SESSION#ACTIVE` |
| AP07 | `Query` | `CUSTOMER#<id>` | `begins_with(SK, "LOCATION#")` |
| AP08 | `GetItem` | `LOCATION#<id>` | `#METADATA` |
| AP09 | `Query GSI1` | `SERIAL#<serial>` | — |
| AP10 | `Query` | `LOCATION#<id>` | `begins_with(SK, "TERMINAL#")` |
| AP11 | `UpdateItem` | `TERMINAL#<id>` | `#METADATA` |
| AP12 | `GetItem` | `LOCATION#<id>` | `TARIFF#<vehicle_type>` |
| AP13 | `Query` | `LOCATION#<id>` | `begins_with(SK, "TARIFF#")` |
| AP14 | `GetItem` | `VEHICLE#<plate>` | `#METADATA` |
| AP15 | `Query` | `LOCATION#<id>` | `begins_with(SK, "PARKING#ACTIVE#")` |
| AP16 | `Query GSI1` | `PLATE#<plate>` | `GSI1SK = "ACTIVE"` |
| AP17 | `TransactWrite` | múltiples | Ver detalle AP17 abajo |
| AP18 | `TransactWrite` | múltiples | Ver detalle AP18 abajo |
| AP19 | `GetItem` | `PARKING#<id>` | `#METADATA` |
| AP20 | `GetItem` | `OPERATOR#<id>` | `SHIFT#ACTIVE` |
| AP21 | `TransactWrite` | múltiples | Ver detalle AP21 abajo |
| AP22 | `Query` | `OPERATOR#<id>` | `begins_with(SK, "SHIFT#")` — filtra `SHIFT#ACTIVE` en código |
| AP23 | `Query` | `SHIFT#<id>` | `begins_with(SK, "PARKING#")` |
| AP24 | `Query` | `SHIFT#<id>` | `begins_with(SK, "TRANSACTION#")` |
| AP25 | `Query` | `LOCATION#<id>` | `between(SK, "PARKING#<date_ini>", "PARKING#<date_fin>")` |
| AP26 | `Query` por shift (N+1) | `SHIFT#<id>` (×N) | `begins_with(SK, "TRANSACTION#")` |
| AP27 | `Query` | `AUDIT#<entity>#<entity_id>` | range en SK por fechas |
| AP28 | `Query GSI2` | `AUDIT_USER#<user_id>` | range en GSI2SK por fechas |

### Detalle de TransactWrite por operación

**AP17 — Ingreso de vehículo (hasta 4 ítems):**
```
1. Put  PARKING#<id> / #METADATA              (ítem principal ParkingSession)
2. Put  LOCATION#<id> / PARKING#ACTIVE#<id>   (ítem activo con GSI1PK=PLATE#...)
3. Put  SHIFT#<shift_id> / PARKING#<entry_at>#<id>  (enlace al shift)
4. Put  VEHICLE#<plate> / #METADATA           (condition: attribute_not_exists — solo crea si no existe)
   ó UpdateItem VEHICLE#<plate> / #METADATA   (actualiza last_seen_at + total_sessions si ya existe)
```

> ⚠️ No se puede hacer `Put` con condition y `Update` en el mismo TransactWrite sobre el mismo ítem. La estrategia recomendada: siempre usar `UpdateItem` con `SET last_seen_at = :now, total_sessions = if_not_exists(total_sessions, :zero) + :one, vehicle_type = if_not_exists(vehicle_type, :type)`.

**AP18 — Salida + cobro (4 ítems):**
```
1. Delete  LOCATION#<id> / PARKING#ACTIVE#<id>              (elimina ítem activo → saca del GSI1)
2. Put     LOCATION#<id> / PARKING#<YYYY-MM-DD>#<id>        (ítem histórico sin GSI1PK)
3. Update  PARKING#<id> / #METADATA                         (marca completed, exit_at, amount)
4. Put     TRANSACTION#<id> / #METADATA                     (registro del pago)
   Put     PARKING#<session_id> / TRANSACTION#<tx_id>       (enlace parking→transaction)
   Put     SHIFT#<shift_id> / TRANSACTION#<tx_at>#<tx_id>   (enlace shift→transaction)
```

> AP18 puede requerir hasta 6 operaciones. Está dentro del límite de 100. El tamaño total raramente supera los 4 MB.

**AP21 — Abrir / cerrar turno (2-3 ítems):**
```
Apertura:
1. Put  OPERATOR#<id> / SHIFT#ACTIVE            (turno activo)
2. Put  SHIFT#<id> / #METADATA                  (ítem principal)

Cierre:
1. Delete  OPERATOR#<id> / SHIFT#ACTIVE
2. Put     OPERATOR#<id> / SHIFT#<started_at>#<id>   (ítem histórico)
3. Update  SHIFT#<id> / #METADATA               (marca closed_at, closing_cash)
```

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
  "updated_at":  "2026-06-14T10:00:00Z",
  "GSI1PK":      "COGNITO#us-east-1_abc|xyz",
  "GSI1SK":      "USER#a1b2c3d4-...",
  "GSI2PK":      "EMAIL#juan.perez@estacionamiento.cl",
  "GSI2SK":      "USER#a1b2c3d4-..."
}
```

> El ítem aparece en GSI1 (via COGNITO) y en GSI2 (via EMAIL) simultáneamente.

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

### Terminal

```json
{
  "PK":                "TERMINAL#t1t1t1t1-...",
  "SK":                "#METADATA",
  "id":                "t1t1t1t1-...",
  "serial_number":     "TUU-2024-001",
  "model":             "TUU Android v3",
  "customer_id":       "c0c0c0c0-...",
  "location_id":       "l1l1l1l1-...",
  "status":            "ONLINE",
  "active_operator_id":"a1b2c3d4-...",
  "app_version":       "2.1.4",
  "last_heartbeat":    "2026-06-14T09:58:00Z",
  "GSI1PK":            "SERIAL#TUU-2024-001",
  "GSI1SK":            "TERMINAL#t1t1t1t1-..."
}
```

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

### Vehículo conocido

```json
{
  "PK":            "VEHICLE#BCDF12",
  "SK":            "#METADATA",
  "plate":         "BCDF12",
  "vehicle_type":  "CAR",
  "first_seen_at": "2026-03-01T10:00:00Z",
  "last_seen_at":  "2026-06-14T09:15:00Z",
  "total_sessions": 7
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
    "minimum_charge": "500.00",
    "grace_minutes":  10
  },
  "GSI1PK": "PLATE#BCDF12",
  "GSI1SK": "ACTIVE"
}
```

### Sesión de estacionamiento completada (historial en Location)

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

> **Sin GSI1PK** — este ítem no tiene `PLATE#` definido, por lo que NO aparece en GSI1. Solo el ítem ACTIVE tiene `GSI1PK`.

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

## Estrategia de evolución del esquema

### Añadir un atributo nuevo a ítems existentes

**Sin migración.** DynamoDB es schemaless. Escribe el nuevo atributo solo en los ítems nuevos. Los ítems existentes simplemente no lo tienen — tu código lo maneja como zero-value / nil.

```go
// Ítem viejo: no tiene `license_plate_country`
// Ítem nuevo: tiene `license_plate_country: "CL"`
// El código lee: if item.LicensePlateCountry == "" { country = "CL" } // default
```

### Añadir un nuevo access pattern (nueva query)

Esto es el equivalente del `ALTER TABLE` en SQL. Se añade un **GSI nuevo** sobre atributos existentes o nuevos:

1. Define el nuevo GSI en Terraform → DynamoDB lo backfilla automáticamente sobre todos los ítems.
2. Los ítems que no tienen el atributo indexado no aparecen en el GSI (sparse behavior).
3. Nuevos ítems escritos con el atributo GSI aparecen inmediatamente.

**Límite:** 20 GSIs por tabla. Con 2 GSIs actualmente, hay margen amplio.

### Añadir una entidad nueva

1. Define sus patrones PK/SK con prefijos nuevos (ej: `VEHICLE#`).
2. Escribe ítems con la nueva estructura — no afecta ítems existentes.
3. Si necesita lookups alternos, reutiliza GSI1 o GSI2 mediante overloading (añade `GSI1PK` al ítem).

### Cambiar PK o SK de ítems existentes (caso difícil)

Este es el único caso que requiere migración de datos:

```
1. Script: leer ítems con PK/SK viejo
2. Escribir ítems con PK/SK nuevo (BatchWrite, chunks de 25)
3. Actualizar el código para escribir en nuevo formato
4. Período de transición: escribir en formato viejo Y nuevo
5. Verificar que no hay lecturas al formato viejo
6. Script: eliminar ítems con formato viejo
```

**Estrategia preventiva:** usar nombres genéricos desde el inicio (`USER#`, `LOCATION#`) en lugar de valores semánticos que podrían cambiar. Ya implementado en este diseño.

### Reserva de atributos GSI para futura expansión

Los atributos `GSI1PK`, `GSI1SK`, `GSI2PK`, `GSI2SK` están definidos genéricamente. Si en el futuro se necesita un GSI3, se añaden atributos `GSI3PK`/`GSI3SK` a los nuevos ítems y se crea el GSI — sin tocar los ítems existentes.

---

## Referencia TransactWrite

| Propiedad | Valor |
|---|---|
| Máx ítems por TransactWrite | **100** |
| Máx tamaño total | **4 MB** |
| Costo | **2× WCU** por ítem (prepare + commit) |
| Idempotencia | Usar `ClientRequestToken` (válido 10 min) |
| No se puede | Operar el mismo ítem dos veces en la misma transacción |
| No se puede | Usar transacciones en indexes directamente |
| Aislamiento | **Serializable** con operaciones individuales (Put/Update/Delete/GetItem) |
| Propagación a GSI | Gradual — no inmediata. Usar `TransactGetItems` si necesitas consistencia fuerte post-write |

**Cuándo NO usar TransactWrite (AWS recomienda):**
- Ingesta masiva de datos → usar `BatchWriteItem` (sin garantías ACID pero más barato)
- Cuando una sola operación es suficiente (no agrupar innecesariamente → peor throughput)
- Cuando los mismos ítems se actualizan frecuentemente en paralelo → conflictos frecuentes

**Idempotencia con ClientRequestToken:**
```go
_, err = client.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{
    ClientRequestToken: aws.String(idempotencyKey), // UUID generado por el caller
    TransactItems: [...]
})
// Si la misma llamada se repite en < 10 min, DynamoDB devuelve éxito sin re-ejecutar
```

---

## Convenciones y reglas

### Formato de claves

- Timestamps en SK: ISO-8601 UTC (`YYYY-MM-DDTHH:MM:SSZ`) — ordenamiento lexicográfico = cronológico.
- Prefijos de entidad en MAYÚSCULAS: `USER#`, `LOCATION#`, `TARIFF#`, `VEHICLE#`, etc.
- UUIDs sin separadores adicionales en las claves.
- Valores enum en MAYÚSCULAS dentro de claves: `TARIFF#CAR`, `SHIFT#ACTIVE`.

### SK fijo para estado activo

`SESSION#ACTIVE` y `SHIFT#ACTIVE` usan SK fijo deliberadamente — permite `GetItem` O(1) para los patrones más frecuentes. Al finalizar, el ítem se **elimina y se crea uno nuevo** con SK histórico (los SKs no se pueden modificar).

### Snapshot de tarifa en ParkingSession

`tariff_snapshot` almacena el precio vigente al momento del ingreso. Protege contra cambios de tarifa durante una estadía activa y elimina un lookup adicional al cobro.

### Nombres de atributos cortos

DynamoDB almacena los nombres de atributos en cada ítem. Nombres muy largos consumen storage. Para atributos muy frecuentes considera abreviaciones (`op_in` vs `operator_id_in`), pero prioriza legibilidad en esta etapa.

### Condición `attribute_not_exists` en writes críticos

Para evitar sobrescribir ítems que no deben modificarse (AuditLog, ítems únicos):

```go
ConditionExpression: aws.String("attribute_not_exists(PK)")
```

Para Vehicle en AP17, usar `UpdateItem` con `if_not_exists` para creación idempotente.

### Separación lexicográfica `ACTIVE` vs fechas

`PARKING#ACTIVE#` > `PARKING#<fecha>#` porque `A` (ASCII 65) > `0-9` (ASCII 48-57). Un `between` sobre fechas ISO nunca incluye los ítems ACTIVE — separación garantizada por el charset, no por lógica de aplicación.

---

## Consideraciones de escala y costo

### Capacidad (billing mode)

| Entorno | Modo recomendado |
|---|---|
| Dev / startup fase inicial | **On-demand (PAY_PER_REQUEST)** — $0 en reposo, se paga por uso |
| Prod con carga predecible | Provisioned + Auto Scaling — más barato a volumen sostenido |
| Prod con spikes impredecibles | On-demand — absorbe spikes sin throttling |

### Proyecciones GSI y su impacto en costo

| Proyección | Storage | Writes | Cuándo usarla |
|---|---|---|---|
| `ALL` | Alto | Alto | Ítems pequeños, frecuencia muy alta, evita second fetch |
| `KEYS_ONLY` | Mínimo | Mínimo | Frecuencia baja/media, acepta second fetch |
| `INCLUDE` | Medio | Medio | Frecuencia alta, solo necesitas un subconjunto de atributos |

### Escala estimada para parking app

| Escenario | Configuración |
|---|---|
| < 50 sedes, < 500 req/s | On-demand, timeout Lambda 30s, sin cambios |
| 50–500 sedes, < 5.000 req/s | On-demand sigue siendo viable; revisar hot partitions en `LOCATION#` más activos |
| > 500 sedes con alta concurrencia | Evaluar write sharding en LOCATION (sufijo de shard en PK) o separar tabla de sesiones activas |

### Rate limit de Cognito (relevante para register/batch)

`AdminCreateUser`: ~50 req/s por defecto. El batch usa chunks de 25 para mantenerse bajo este límite. Si se incrementa la escala, solicitar aumento de límite a AWS o usar exponential backoff.

### Herramientas recomendadas

| Herramienta | Para qué |
|---|---|
| [NoSQL Workbench](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/workbench.html) | Modelado visual, visualización de datos, query development local |
| [aws-sdk-go-v2 dynamodb](https://pkg.go.dev/github.com/aws/aws-sdk-go-v2/service/dynamodb) | SDK oficial Go |
| [dynamodbattribute](https://pkg.go.dev/github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue) | Marshal/Unmarshal Go structs ↔ DynamoDB AttributeValue |
| DynamoDB Local | Testing local sin costo — imagen Docker `amazon/dynamodb-local` |
| CloudWatch Metrics | Monitorear `ConsumedReadCapacityUnits`, `ConsumedWriteCapacityUnits`, `TransactionConflict` |
