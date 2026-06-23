# US-008 — Gestión de Terminales

## Propósito

Los terminales son registros de dispositivos físicos POS (TUU) asociados a una organización. Su único rol de negocio es **impedir que un OPERATOR inicie sesión desde dos terminales distintas de forma simultánea**. Un terminal **no está vinculado a ninguna locación específica**: la información de locación del OPERATOR proviene de su propio `location_id` asignado como usuario.

## Historias de Usuario

| ID | Historia |
|----|----------|
| US-008-A | **Como** ADMIN, **quiero** registrar un terminal POS en una organización, **para** habilitarlo para el login de operadores y controlar sesiones simultáneas. |
| US-008-B | **Como** ADMIN o CUSTOMER, **quiero** listar los terminales de mi organización, **para** conocer el estado de cada dispositivo. |
| US-008-C | **Como** ADMIN o CUSTOMER, **quiero** ver el detalle de un terminal, **para** conocer su estado y el operador activo en él. |
| US-008-D | **Como** ADMIN, **quiero** actualizar los datos de un terminal, **para** corregir su modelo o versión de app. |
| US-008-E | **Como** ADMIN, **quiero** eliminar un terminal, **para** darlo de baja del inventario. |
| US-008-F | **Como** ADMIN, **quiero** poner un terminal en mantenimiento o sacarlo, **para** bloquearlo temporalmente sin eliminarlo. |

---

## Modelo de Datos

```json
{
  "id":               "uuid",
  "serialNumber":     "TUU-2024-001",
  "model":            "TUU Pro",
  "orgId":            "uuid-org",
  "status":           "OFFLINE",
  "activeOperatorId": null,
  "appVersion":       "2.3.1",
  "lastHeartbeat":    "2024-01-15T10:29:00Z"
}
```

> `locationId` fue eliminado: los terminales ya no se vinculan a locaciones. La locación de operación de un OPERATOR proviene de `users.location_id` del usuario que hace login, no del terminal.

### Ciclo de Vida del Estado (`terminal_status`)

```
OFFLINE ──[operator login]──► ONLINE
ONLINE  ──[operator logout / shift close]──► OFFLINE
OFFLINE ──[manual]──► MAINTENANCE
MAINTENANCE ──[manual]──► OFFLINE
ONLINE  no puede ir directo a MAINTENANCE (debe cerrarse la sesión primero)
```

| Estado | Logins permitidos | Descripción |
|--------|:-----------------:|-------------|
| `OFFLINE`      | ✓ | Disponible para login de operador |
| `ONLINE`       | ✗ | Tiene un operador activo |
| `MAINTENANCE`  | ✗ | Bloqueado para mantenimiento técnico |

---

## Endpoints

| Método | Ruta | Rol mínimo | Descripción |
|--------|------|------------|-------------|
| `POST`   | `/terminals`                      | ADMIN    | Registrar terminal |
| `GET`    | `/terminals?orgId=`               | CUSTOMER | Listar terminales por organización |
| `GET`    | `/terminals/{id}`                 | CUSTOMER | Ver terminal |
| `PUT`    | `/terminals/{id}`                 | ADMIN    | Actualizar terminal |
| `DELETE` | `/terminals/{id}`                 | ADMIN    | Eliminar terminal |
| `POST`   | `/terminals/{id}/maintenance`     | ADMIN    | Poner en mantenimiento |
| `POST`   | `/terminals/{id}/offline`         | ADMIN    | Sacar de mantenimiento (→ OFFLINE) |

> **Nota de acceso**: el rol OPERATOR accede a la información del terminal **exclusivamente** a través de `GET /pos/bootstrap/{serialNumber}`. No tiene acceso a estos endpoints REST.

---

## US-008-A — Registrar Terminal

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede registrar terminales. |
| AC-2 | `serialNumber`, `model` y `orgId` son obligatorios. |
| AC-3 | El `serialNumber` debe ser único en todo el sistema. Si ya existe, retorna `409` con `serial_number_ya_registrado`. |
| AC-4 | El `serialNumber` es la identidad física del dispositivo: una vez registrado, no puede modificarse. |
| AC-5 | El terminal se registra con estado `OFFLINE`. |
| AC-6 | Retorna `201 Created` con el objeto completo. |

### Request

```
POST /terminals
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "serialNumber": "TUU-2024-001",
  "model":        "TUU Pro",
  "orgId":        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "appVersion":   "2.3.1"
}
```

### Response `201 Created`

```json
{
  "id":               "dddddddd-dddd-dddd-dddd-dddddddddddd",
  "serialNumber":     "TUU-2024-001",
  "model":            "TUU Pro",
  "orgId":            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "status":           "OFFLINE",
  "activeOperatorId": null,
  "appVersion":       "2.3.1",
  "lastHeartbeat":    null
}
```

### Errores

| HTTP | Código | Causa |
|------|--------|-------|
| 400  | `campos_requeridos_faltantes` | Falta `serialNumber`, `model` u `orgId` |
| 404  | `organizacion_no_encontrada` | El `orgId` no existe |
| 409  | `serial_number_ya_registrado` | `serialNumber` duplicado en el sistema |

---

## US-008-B — Listar Terminales

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN sin filtros retorna todos. Con `?orgId=` filtra por organización. |
| AC-2 | CUSTOMER debe filtrar por su `orgId`. Si pasa el `orgId` de otra organización, retorna `403`. |
| AC-3 | Retorna `200 OK` con lista vacía si no hay terminales. |

---

## US-008-C — Obtener Terminal

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | ADMIN: cualquier terminal. CUSTOMER: solo terminales de su organización. |
| AC-2 | Si el ID no existe, retorna `404` con `terminal_no_encontrado`. |

---

## US-008-D — Actualizar Terminal

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede actualizar terminales. |
| AC-2 | Campos actualizables: `model`, `appVersion`. `serialNumber` y `orgId` son inmutables. |
| AC-3 | No se puede actualizar si el terminal está `ONLINE` (tiene operador activo). Retorna `409` con `terminal_online`. |
| AC-4 | Si el body está vacío o solo contiene campos inmutables, retorna `400` con `sin_campos_para_actualizar`. |

### Request

```
PUT /terminals/{id}
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "model":      "TUU Pro V2",
  "appVersion": "2.4.0"
}
```

### Response `200 OK`

Retorna el objeto completo con los cambios aplicados.

### Errores

| HTTP | Código | Causa |
|------|--------|-------|
| 409  | `terminal_online` | No se puede modificar un terminal con operador activo |

---

## US-008-E — Eliminar Terminal

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede eliminar terminales. |
| AC-2 | No se puede eliminar si está `ONLINE` (tiene operador activo). Retorna `409` con `terminal_online`. |
| AC-3 | Si el ID no existe, retorna `404`. |

### Diagrama de Secuencia

```mermaid
sequenceDiagram
    actor ADM as Dashboard (ADMIN)
    participant API as TerminalService
    participant DB  as PostgreSQL

    ADM->>API: DELETE /terminals/{id}

    API->>DB: SELECT * FROM terminals WHERE id = ?
    alt No existe
        API-->>ADM: 404 terminal_no_encontrado
    end

    alt status = ONLINE
        API-->>ADM: 409 terminal_online
    end

    API->>DB: DELETE FROM terminals WHERE id = ?
    DB-->>API: OK

    API-->>ADM: 200 OK
```

---

## US-008-F — Mantenimiento y Recuperación

### Criterios de Aceptación

| # | Criterio |
|---|----------|
| AC-1 | Solo ADMIN puede cambiar el estado a `MAINTENANCE` o `OFFLINE`. |
| AC-2 | Solo un terminal en estado `OFFLINE` puede pasar a `MAINTENANCE`. Si está `ONLINE`, retorna `409` con `terminal_online`. |
| AC-3 | Un terminal en `MAINTENANCE` puede volver a `OFFLINE` vía `POST /terminals/{id}/offline`. |
| AC-4 | `MAINTENANCE` bloquea el login de cualquier operador en ese terminal. En `deviceLogin`, si el terminal está en `MAINTENANCE`, retorna `403` con `terminal_en_mantenimiento`. |

### Diagrama de Secuencia — Poner en Mantenimiento

```mermaid
sequenceDiagram
    actor USR as Dashboard (ADMIN)
    participant API as TerminalService
    participant DB  as PostgreSQL

    USR->>API: POST /terminals/{id}/maintenance

    API->>DB: SELECT * FROM terminals WHERE id = ?
    alt No existe
        API-->>USR: 404 terminal_no_encontrado
    end

    alt status = ONLINE
        API-->>USR: 409 terminal_online (cierra sesión primero)
    end

    API->>DB: UPDATE terminals SET status = 'MAINTENANCE' WHERE id = ?
    DB-->>API: OK

    API-->>USR: 200 { "status": "MAINTENANCE" }
```

---

## Relación con Auth Flow

El estado del terminal es validado en `POST /auth/device` (US-001):
- `ONLINE` → bloquea nuevo login (`terminal_no_disponible`)
- `MAINTENANCE` → bloquea nuevo login (`terminal_en_mantenimiento`)
- `OFFLINE` → permite login

Cuando el operador hace login exitoso:
1. Terminal → `ONLINE`
2. `active_operator_id` = `user.id`
3. La locación del operador se determina desde `users.location_id`, no desde el terminal.

Cuando el operador hace logout:
1. Terminal → `OFFLINE`
2. `active_operator_id` = `NULL`

---

## Regla de Dominio — Prevención de Sesión Simultánea

| Regla | Detalle |
|-------|---------|
| **Un terminal → un operador activo** | `terminals.active_operator_id` registra qué OPERATOR está usando el terminal. Si está `ONLINE`, ningún otro operador puede hacer login en ese terminal. |
| **Un operador → un terminal activo** | La tabla `user_sessions` tiene `UNIQUE(user_id)`. Si el mismo OPERATOR intenta hacer login en un segundo terminal, el sistema detecta la sesión existente y bloquea la nueva con `operador_ya_conectado`. |
| **Sin validación cruzada con locaciones** | El terminal solo valida su propio estado (`OFFLINE`/`ONLINE`/`MAINTENANCE`) y la unicidad de sesión del OPERATOR. No hay restricción geográfica a nivel de terminal. |

---

## Reglas de Aislamiento

| Operación | ADMIN | CUSTOMER | OPERATOR |
|-----------|-------|----------|----------|
| Registrar | Cualquier org | ✗ | ✗ |
| Listar | Todos / por org | Solo su org | vía bootstrap |
| Ver | Cualquiera | Solo su org | vía bootstrap |
| Actualizar | Cualquiera | ✗ | ✗ |
| Eliminar | Cualquiera | ✗ | ✗ |
| Mantenimiento / Offline | Cualquiera | ✗ | ✗ |

---

## Archivos Relacionados

| Archivo | Rol |
|---------|-----|
| `terminal/TerminalController.java` | REST endpoints |
| `terminal/TerminalService.java` | RBAC + validaciones de estado y sesión |
| `terminal/TerminalRepository.java` | `findById`, `findByOrgId`, `insert`, `update`, `delete`, `setStatus`, `setActiveOperator` |
| `terminal/Terminal.java` | Record de dominio (sin `locationId`) |
| `terminal/CreateTerminalRequest.java` | Body de creación (sin `locationId`) |
| `terminal/UpdateTerminalRequest.java` | Body de actualización (`model`, `appVersion`) |
| `auth/AuthService.java` | Check de estado del terminal en `deviceLogin`; detección de sesión simultánea |
