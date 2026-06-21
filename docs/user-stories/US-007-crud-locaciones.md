# US-007 — CRUD de Locaciones

## Historias de Usuario

| ID | Historia |
|----|----------|
| US-007-A | **Como** ADMIN o CUSTOMER, **quiero** crear una locación dentro de una organización, **para** definir los puntos físicos de estacionamiento. |
| US-007-B | **Como** ADMIN o CUSTOMER, **quiero** listar las locaciones, **para** tener visibilidad de los puntos bajo mi gestión. |
| US-007-C | **Como** cualquier usuario autenticado, **quiero** ver el detalle de una locación, **para** conocer su configuración (zona horaria, estado). |
| US-007-D | **Como** ADMIN o CUSTOMER, **quiero** actualizar los datos de una locación, **para** corregir información o cambiar la zona horaria. |
| US-007-E | **Como** ADMIN, **quiero** eliminar una locación, **para** retirarla del sistema cuando ya no opere. |
| US-007-F | **Como** ADMIN o CUSTOMER, **quiero** activar o desactivar una locación, **para** suspender operaciones en un punto sin eliminarlo. |

---

## Modelo de Datos

```json
{
  "id":             "uuid",
  "orgId":          "uuid-org",
  "locationName":   "Sucursal Centro",
  "address":        "Av. Bernardo O'Higgins 1234",
  "city":           "Santiago",
  "timezone":       "America/Santiago",
  "capacity":       150,
  "locationStatus": "ACTIVE",
  "createdAt":      "2024-01-15T10:30:00Z"
}
```

> `timezone` es crítica: turnos, ingresos y egresos de vehículos se interpretan en la zona horaria de la locación.
> `capacity` es el número de puestos físicos de estacionamiento. Se gestiona en este CRUD (creación y actualización) y el POS lo recibe como dato informativo vía `GET /pos/bootstrap/{serialNumber}`.
>
>
> **Nota de acceso**: el rol OPERATOR accede a la información de locaciones **exclusivamente** a través de `GET /pos/bootstrap/{serialNumber}`. No tiene acceso a los endpoints REST de este CRUD.

---

## Endpoints

| Método | Ruta | Rol mínimo | Descripción |
|--------|------|------------|-------------|
| `POST`   | `/locations`                    | ADMIN    | Crear locación |
| `GET`    | `/locations?orgId=`             | CUSTOMER | Listar locaciones (web dashboard) |
| `GET`    | `/locations/{id}`               | CUSTOMER | Ver locación (web dashboard) |
| `PUT`    | `/locations/{id}`               | CUSTOMER | Actualizar locación |
| `DELETE` | `/locations/{id}`               | ADMIN    | Eliminar locación |
| `POST`   | `/locations/{id}/activate`      | CUSTOMER | Activar locación |
| `POST`   | `/locations/{id}/deactivate`    | CUSTOMER | Desactivar locación |

---

## US-007-A — Crear Locación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede crear locaciones. |
| AC-2 | `locationName`, `address`, `city` y `orgId` son obligatorios. |
| AC-3 | Si `timezone` no se envía, se usa `America/Santiago` por defecto. |
| AC-4 | `capacity` (número de puestos físicos) es obligatorio y debe ser `>= 1`. |
| AC-5 | La locación se crea en estado `ACTIVE`. |
| AC-6 | Retorna `201 Created` con el objeto completo. |

### Request

```
POST /locations
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "orgId":        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "locationName": "Sucursal Centro",
  "address":      "Av. Bernardo O'Higgins 1234",
  "city":         "Santiago",
  "timezone":     "America/Santiago",
  "capacity":     150
}
```

### Response `201 Created`

```json
{
  "id":             "cccccccc-cccc-cccc-cccc-cccccccccccc",
  "orgId":          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "locationName":   "Sucursal Centro",
  "address":        "Av. Bernardo O'Higgins 1234",
  "city":           "Santiago",
  "timezone":       "America/Santiago",
  "capacity":       150,
  "locationStatus": "ACTIVE",
  "createdAt":      "2024-01-15T10:30:00Z"
}
```

### Errores

| HTTP | Código | Causa |
|------|--------|-------|
| 400  | `campos_requeridos_faltantes` | Falta `orgId`, `locationName`, `address`, `city` o `capacity` |
| 400  | `capacity_invalido` | `capacity` es menor a `1` |
| 404  | `organizacion_no_encontrada` | El `orgId` no existe |

---

## US-007-B — Listar Locaciones

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN sin filtro retorna todas las locaciones. Con `?orgId=` filtra por organización. |
| AC-2 | CUSTOMER debe pasar `?orgId=` de su propia organización. Si pasa otra, retorna `403`. |
| AC-3 | Retorna `200 OK` con lista vacía si no hay locaciones. |

### Request

```
GET /locations?orgId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
Authorization: Bearer {accessToken}
```

### Response `200 OK`

```json
[
  {
    "id":             "cccccccc-cccc-cccc-cccc-cccccccccccc",
    "orgId":          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "locationName":   "Sucursal Centro",
    "city":           "Santiago",
    "locationStatus": "ACTIVE",
    "timezone":       "America/Santiago"
  }
]
```

---

## US-007-C — Obtener Locación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN puede ver cualquier locación. |
| AC-2 | CUSTOMER puede ver solo locaciones de su organización. |
| AC-3 | Si el ID no existe, retorna `404` con `locacion_no_encontrada`. |

---

## US-007-D — Actualizar Locación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN puede actualizar cualquier locación. |
| AC-2 | CUSTOMER puede actualizar solo locaciones de su organización. |
| AC-3 | Campos actualizables: `locationName`, `address`, `city`, `timezone`, `capacity`. |
| AC-4 | `capacity` debe ser `>= 1` si se envía. |
| AC-5 | `orgId` no se puede modificar (pertenencia de la locación es inmutable). |
| AC-6 | Si el body está vacío, retorna `400` con `sin_campos_para_actualizar`. |

### Request

```
PUT /locations/{id}
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "locationName": "Sucursal Centro (Piso 2)",
  "timezone":     "America/Santiago",
  "capacity":     200
}
```

### Response `200 OK`

Retorna el objeto completo con los cambios aplicados.

---

## US-007-E — Eliminar Locación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede eliminar locaciones. |
| AC-2 | No se puede eliminar si tiene terminales registradas. Retorna `409` con `locacion_tiene_terminales`. |
| AC-3 | No se puede eliminar si tiene sesiones de estacionamiento activas (`ACTIVE`). Retorna `409` con `locacion_tiene_sesiones_activas`. |
| AC-4 | Si el ID no existe, retorna `404`. |

### Diagrama de Secuencia

```mermaid
sequenceDiagram
    actor ADM as Dashboard (ADMIN)
    participant API as LocationService
    participant DB  as PostgreSQL

    ADM->>API: DELETE /locations/{id}

    API->>DB: SELECT id FROM locations WHERE id = ?
    alt No existe
        API-->>ADM: 404 locacion_no_encontrada
    end

    API->>DB: SELECT COUNT(*) FROM terminals WHERE location_id = ?
    alt Tiene terminales
        API-->>ADM: 409 locacion_tiene_terminales
    end

    API->>DB: SELECT COUNT(*) FROM parking_sessions<br/>WHERE location_id = ? AND status = 'ACTIVE'
    alt Tiene sesiones activas
        API-->>ADM: 409 locacion_tiene_sesiones_activas
    end

    API->>DB: DELETE FROM locations WHERE id = ?
    DB-->>API: OK

    API-->>ADM: 200 OK
```

---

## US-007-F — Activar / Desactivar Locación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN o CUSTOMER (propia org) pueden cambiar el estado. |
| AC-2 | Al desactivar, los turnos y sesiones activas en esa locación continúan hasta su cierre natural. El bloqueo aplica a **nuevas** operaciones. |
| AC-3 | Si se intenta abrir un turno en una locación `INACTIVE`, retorna `403` con `locacion_inactiva`. |

### Response `200 OK`

```json
{ "locationStatus": "INACTIVE" }
```

---

## Reglas de Aislamiento

| Operación | ADMIN | CUSTOMER | OPERATOR |
|-----------|-------|----------|----------|
| Crear | Cualquier org | ✗ | ✗ |
| Listar | Todas / por org | Solo su org | vía bootstrap |
| Ver | Cualquiera | Solo su org | vía bootstrap |
| Actualizar | Cualquiera | Solo su org | ✗ |
| Eliminar | Cualquiera | ✗ | ✗ |
| Activar/Desactivar | Cualquiera | Solo su org | ✗ |

> "vía bootstrap" significa que el OPERATOR recibe esta información en `GET /pos/bootstrap/{serialNumber}`, no mediante estos endpoints REST.

---

## Regla de Dominio — Seriales de Máquinas por Locación

Cada locación tiene un conjunto fijo de números de serie (máquinas POS TUU) asociados a ella. Esta asociación es **exclusiva**: un serial pertenece a una y solo una locación, y no puede reasignarse a otra.

| Regla | Detalle |
|-------|---------|
| **Un serial → una locación** | La tabla `terminals` tiene `UNIQUE(serial_number)` y `NOT NULL location_id`. No hay seriales sin locación ni compartidos. |
| **Binding a nivel de locación** | La pertenencia es `terminal.location_id`, no `terminal.org_id`. Dos locaciones de la misma organización tienen conjuntos de seriales separados. |
| **Solo ADMIN gestiona la asociación** | El registro de un terminal (US-008) lo realiza solo ADMIN, asignando el serial a una locación concreta en ese momento. |
| **El POS valida su serial en bootstrap** | `GET /pos/bootstrap/{serialNumber}` verifica que el serial del path coincide con el terminal registrado en `user_sessions` del caller. Un serial de otra locación es rechazado con `403`. |

> Esta regla garantiza que un operador que hace login con el dispositivo A no pueda operar en la locación B aunque pertenezcan a la misma organización.

---

## Archivos Relacionados

| Archivo | Rol |
|---------|-----|
| `location/LocationController.java` | REST endpoints |
| `location/LocationService.java` | RBAC + validaciones de eliminación |
| `location/LocationRepository.java` | `findById`, `findByOrgId`, `findAll`, `insert`, `update`, `delete`, `setStatus`, `hasTerminals`, `hasActiveParkingSessions` |
| `location/Location.java` | Record de dominio |
| `location/CreateLocationRequest.java` | Body de creación |
| `location/UpdateLocationRequest.java` | Body de actualización |
