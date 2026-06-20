---
name: feature-docs
description: >
  When a feature is marked as complete in api/openapi.yaml, generates a Markdown
  usage-flow document in docs/features/ explaining how to use it end-to-end.
  Trigger: After updating api/openapi.yaml with a completed endpoint.
metadata:
  version: "1.0"
  scope: [root]
  auto_invoke:
    - "After updating api/openapi.yaml with a completed endpoint"
    - "Documenting a completed feature"
    - "Feature is ready and needs usage documentation"
    - "Documentar funcionalidad terminada"
    - "Generar documentación de una feature completa"
    - "La feature está lista, necesita documentación"
allowed-tools: Read, Write, Glob, Grep
---

## When to Run This Skill

Run after:
1. The handler is implemented and working
2. `api/openapi.yaml` has been updated with the endpoint
3. The user confirms the feature is complete

Do NOT run for stubs, partial implementations, or endpoints that are still changing.

---

## Output Location

```
docs/features/{NN}-{feature-slug}.md
```

- `NN` — two-digit sequence number (01, 02, 03…)
- `feature-slug` — kebab-case name of the feature

Check existing files in `docs/features/` to pick the next sequence number.

---

## How to Generate the Document

### Step 1 — Read the endpoint from openapi.yaml

Find the relevant `operationId` in `api/openapi.yaml` and extract:
- HTTP method and path
- Summary and description
- Parameters (path, query)
- Request body schema (if any)
- All response schemas (success + errors)

### Step 2 — Read the handler implementation

Find the corresponding handler and extract:
- Actual validation logic
- What it calls internally
- Any edge cases or caveats not in the OpenAPI spec

### Step 3 — Write the document using the template below

---

## Document Template

````markdown
# {Feature Title}

> `{METHOD} {path}` — {one-line summary from OpenAPI}

## Overview

{2–4 sentences explaining what this feature does.}

## Prerequisites

{Bulleted list of what must be in place before using this endpoint.}

## Request

### Endpoint

```
{METHOD} /api/v1/{path}
```

### Request Body (only if applicable)

```json
{
  "field": "value"
}
```

## Response

### Success — `{status code}`

```json
{
  "data": { ... }
}
```

## Error Cases

| Code | HTTP | When |
|------|------|------|
| `{DOMAIN_ERROR_CODE}` | `{status}` | {condition} |

## Example

### Request

```bash
curl -X {METHOD} http://localhost:8080/api/v1/{path} \
  -H "Content-Type: application/json" \
  -d '{...}'
```

### Response

```json
{
  "data": { ... }
}
```
````

---

## Content Rules

- Write for a developer integrating this API for the first time
- Use real example values (fake ARNs, fake account IDs, real UUID format)
- Keep code blocks copy-pasteable
- No implementation details (no internal types or package names)

---

## After Writing the Document

Update `docs/features/README.md` (create it if it doesn't exist) with a one-line
entry for the new document.
