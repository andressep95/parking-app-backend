---
name: openapi
description: >
  Keeps api/openapi.yaml in sync with the Spring controllers in src/main/java.
  Trigger: After adding, modifying, or deleting any HTTP endpoint or changing a
  request/response schema.
metadata:
  version: "3.0"
  scope: [root, src/main]
  auto_invoke:
    - "Adding a new endpoint"
    - "Modifying a Spring controller"
    - "Changing API responses"
    - "Changing request or response schema"
    - "Deleting an endpoint"
    - "Agregar o modificar un endpoint"
    - "Sincronizar especificación openapi"
    - "Cambiar esquema de request o response"
allowed-tools: Read, Edit, Write, Glob, Grep
---

## File Location

```
api/openapi.yaml   ← single source of truth for the API contract
```

Always edit this file after any handler change. Never let it drift from the code.

---

## Critical Rules

- ALWAYS update `api/openapi.yaml` after any endpoint change
- NEVER document a field in OpenAPI that doesn't exist in the Java DTO/record
- ALWAYS use `$ref` for schemas used in more than one place
- Response envelope MUST match the `ApiResponse<T>` record in `shared/api/`
- `operationId` MUST be unique and match the Spring controller method name exactly
- ALWAYS read `api/openapi.yaml` before adding a new endpoint (check for naming conflicts)

---

## Controller → OpenAPI Mapping

For every Spring controller method, add or update a path entry:

```yaml
paths:
  /api/v1/accounts/{id}/resources:
    get:
      operationId: listResources
      summary: List discovered resources for an account
      tags: [discovery]
      parameters:
        - $ref: '#/components/parameters/AccountId'
      responses:
        '200':
          description: Resources listed successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ResourceListResponse'
        '404':
          $ref: '#/components/responses/NotFound'
        '500':
          $ref: '#/components/responses/InternalError'
```

---

## HTTP Method → OpenAPI Verb

| Spring annotation | OpenAPI verb | Typical status |
|-------------------|--------------|----------------|
| `@PostMapping` | `post` | 201 (created) / 202 (async accepted) |
| `@GetMapping` | `get` | 200 |
| `@PutMapping` | `put` | 200 |
| `@PatchMapping` | `patch` | 200 |
| `@DeleteMapping` | `delete` | 204 |

---

## Tags

| Tag | Endpoints |
|-----|-----------|
| `accounts` | connect, status, delete |
| `discovery` | discover, resources, graph |
| `activation` | activate |
| `projects` | project-scoped resources |

---

## Workflow

1. Add or modify the controller in `src/main/java/.../api/`
2. Read `api/openapi.yaml` to check for naming conflicts and existing patterns
3. Add or update the path entry — reuse `$ref` components where possible
4. If a new schema is needed, add it to `components/schemas/`
5. Verify `operationId` matches the Spring controller method name exactly

---

## Checklist

- [ ] Path entry exists for every `@RequestMapping` method in the controller
- [ ] `operationId` matches the Spring controller method name
- [ ] Request body schema matches the Java DTO / record class
- [ ] Response schema matches the `ApiResponse<T>` envelope
- [ ] All error responses use `$ref` to standard error responses
- [ ] New schemas are in `components/schemas/`, not inlined
- [ ] Tags are consistent with the tags table above
