---
name: endpoint-trace
description: >
  Generates code-level trace documents for each HTTP endpoint, mapping the full
  call chain from the controller inward through every component it touches
  (services, repositories, AWS clients, shared utilities). Output lives in
  docs/traces/ and is meant for developers navigating the codebase, not end users.
  Trigger: When documenting a new endpoint at the code level, auditing dependencies
  of an existing endpoint, or creating an endpoint-to-component map.
metadata:
  version: "1.0"
  scope: [root]
  auto_invoke:
    - "document endpoint code trace"
    - "trace endpoint dependencies"
    - "map endpoint call chain"
    - "create code-level endpoint doc"
    - "documentar endpoint, trazar dependencias"
    - "crear endpoint REST, API HTTP"
    - "mapear cadena de llamadas de un endpoint"
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

## When to Use

- After implementing a new endpoint and its full service chain
- When a developer needs to understand which components an endpoint touches
- When auditing which AWS SDK calls are made per endpoint
- When planning a refactor and needing to know the blast radius

---

## Critical Rules

- **Read the actual source** — never infer the call chain; always read the controller, service, and every class it calls
- **One document per endpoint** — each HTTP method+path gets its own file
- **Output location**: `docs/traces/{NN}-{method}-{slug}.md` (e.g., `01-post-accounts-connect.md`)
- **Never duplicate** — check `docs/traces/` for existing files before creating
- **Only document what the code actually does** — no speculation about future behavior

---

## Output Location

```
docs/traces/{NN}-{http-method}-{path-slug}.md
```

- `NN` — two-digit sequence (01, 02…). Check existing files for the next number.
- `http-method` — lowercase (`get`, `post`, `put`, `delete`)
- `path-slug` — path kebab-cased, drop `{id}` brackets (e.g., `accounts-id-config-activate`)

Examples:
```
01-post-accounts.md
02-post-accounts-id-verify.md
03-get-accounts-id.md
04-delete-accounts-id.md
05-put-accounts-id-discovery-source.md
06-get-accounts-id-config-status.md
07-post-accounts-id-config-activate.md
```

---

## How to Generate a Trace Document

### Step 1 — Find the controller method

Grep for the path in the controllers:
```bash
grep -r "config/activate\|configActivat" src/main/java --include="*.java" -l
```
Read the controller class, find the handler method, note:
- Exact method signature
- Which service(s) it calls
- What it returns

### Step 2 — Trace each service call

Read each service class the controller calls. For each service method, note:
- What it calls next (repositories, AWS clients, other services)
- What domain objects it touches
- What exceptions it can throw

### Step 3 — Trace repositories and AWS clients

Read repository interfaces + adapters. Read AWS client usage — note the exact SDK method called (`s3Client.createBucket`, `stsClient.assumeRole`, etc.) and the AWS service it maps to.

### Step 4 — Write the document

Use the template below.

---

## Document Template

````markdown
# {HTTP METHOD} {/path}

> `{ControllerClass}.{methodName}()` — {one-line summary of what this endpoint does}

## Call Chain

```
{ControllerClass}.{method}(params)
  └── {ServiceClass}.{method}(params)
        ├── {RepositoryInterface}.{method}()          [domain port]
        │     └── {JpaAdapterClass}.{method}()        [JPA]
        ├── {SharedService}.{method}()
        │     └── [{AWS_SERVICE}] SdkClient.sdkMethod()
        ├── {AnotherService}.{method}()
        │     ├── [{AWS_SERVICE}] SdkClient.sdkMethod()
        │     └── [{AWS_SERVICE}] SdkClient.sdkMethod()
        └── {HelperMethod}()
```

## Components

| Component | Layer | File |
|-----------|-------|------|
| `{ControllerClass}` | `api` | `{bounded-context}/api/{ControllerClass}.java` |
| `{ServiceClass}` | `application` | `{bounded-context}/application/{ServiceClass}.java` |

## AWS Calls

| Step | SDK Client | Method | AWS Service | Purpose |
|------|-----------|--------|-------------|---------|
| 1 | `{ClientType}` | `.{sdkMethod}()` | {AWS Service name} | {what it does} |

> Omit this section if the endpoint makes no AWS calls.

## Error Paths

| Exception | Thrown by | Maps to |
|-----------|-----------|---------|
| `{ExceptionClass}` | `{ComponentClass}` | `{HTTP_STATUS} {ERROR_CODE}` |
````

---

## Call Chain Notation

Use ASCII tree with these prefixes:

```
└──   last child
├──   non-last child
│     continuation line (vertical bar for indentation)
```

Labels: `[JPA]`, `[STS]`, `[S3]`, `[IAM]`, `[Config]`, `[CFN]`, `[Explorer]`, `[Cache]`, `[HTTP]`

---

## After Writing the Document

1. Create `docs/traces/README.md` if it doesn't exist.
2. Add a one-line entry linking to the new trace document.

---

## Checklist

- [ ] Read every class in the chain — no inferred steps
- [ ] All AWS SDK calls listed in the AWS Calls table
- [ ] All exception paths documented in Error Paths
- [ ] File named correctly: `{NN}-{method}-{slug}.md`
- [ ] `docs/traces/README.md` updated
