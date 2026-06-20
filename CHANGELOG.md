# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### [ad9d558] — 2026-06-20

**feat(project): migrate from Go Lambdas to Spring Boot 4.1**

> what: Replaces Go Lambda handlers (auth, user, organization) with a
> Spring Boot 4.1 + Java 21 monolith using PostgreSQL, Spring
> Security OAuth2 Resource Server, and AWS Cognito via SDK v2.
> why:  The Lambda architecture required separate deployments per domain

#### Added

- `.agents/memory/token-usage.jsonl`
- `.agents/rules.md`
- `.agents/scripts/extract_changes.py`
- `.agents/scripts/generate-changelog.py`
- `.agents/scripts/init.sh`
- `.agents/scripts/post-commit`
- `.agents/scripts/query-all.py`
- `.agents/scripts/query-memory.py`
- `.agents/scripts/requirements.txt`
- `.agents/scripts/scan-history.sh`
- `.agents/scripts/session-start.sh`
- `.agents/scripts/sync-skills-to-chroma.py`
- `.agents/scripts/sync.sh`
- `.agents/scripts/token-report.py`
- `.agents/scripts/token-tracker.py`
- `.agents/scripts/user-prompt-submit.sh`
- `.agents/scripts/validate-commit.sh`
- `.agents/skills/311-frameworks-spring-jdbc/SKILL.md`
- `.agents/skills/311-frameworks-spring-jdbc/references/311-frameworks-spring-jdbc.md`
- `.agents/skills/commit/SKILL.md`
- _…and 88 more_

#### Changed

- `README.md`

#### Removed

- `auth-handler/README.md`
- `auth-handler/docs/openapi.yaml`
- `auth-handler/dynamo.go`
- `auth-handler/go.mod`
- `auth-handler/go.sum`
- `auth-handler/handler.go`
- `auth-handler/login.go`
- `auth-handler/main.go`
- `auth-handler/register.go`
- `auth-handler/register_batch.go`
- `auth-handler/rut/normalize.go`
- `auth-handler/rut/validate.go`
- `auth-handler/session.go`
- `docs/dynamodb-schema.md`
- `organization-handler/README.md`
- `organization-handler/delete.go`
- `organization-handler/docs/openapi.yaml`
- `organization-handler/get.go`
- `organization-handler/go.mod`
- `organization-handler/go.sum`
- _…and 19 more_

---

### [546b7b0] — 2026-06-17

**feat: session management + schema fixes**

> auth-handler:
> - Login registra SESSION#ACTIVE en DynamoDB (TTL 24h)
> - POST /api/v1/auth/logout: cierra sesión propia (GlobalSignOut + delete DynamoDB)
> - DELETE /api/v1/auth/sessions/{user_id}: ADMIN cierra sesión remota

#### Added

- `auth-handler/session.go`

#### Changed

- `auth-handler/dynamo.go`
- `auth-handler/handler.go`
- `auth-handler/login.go`
- `docs/dynamodb-schema.md`

---

### [fe15c32] — 2026-06-17

**Merge pull request #1 from andressep95/feature/organization-handler**

> Feature/organization handler

_No file changes detected._

---

### [622a7e3] — 2026-06-17

**feat: add organization-handler Lambda**

> CRUD de organizaciones sobre DynamoDB single-table.
> ADMIN puede gestionar todas las orgs; CUSTOMER accede solo a la suya
> verificando su sub via GSI1. Incluye docs OpenAPI y README.

#### Added

- `organization-handler/README.md`
- `organization-handler/delete.go`
- `organization-handler/docs/openapi.yaml`
- `organization-handler/get.go`
- `organization-handler/go.mod`
- `organization-handler/go.sum`
- `organization-handler/handler.go`
- `organization-handler/list.go`
- `organization-handler/main.go`
- `organization-handler/model.go`
- `organization-handler/organization-handler`
- `organization-handler/status.go`
- `organization-handler/update.go`

---

### [bf78b2b] — 2026-06-17

**feat: add Organization entity and restructure user ownership model**

> - Organization is now a first-class DynamoDB entity (ORGANIZATION#<id>/#METADATA)
> with its own item: org_name, rut_empresa, org_status, admin_user_id, created_at
> - ADMIN creates Organization + CUSTOMER user atomically in a single TransactWrite
> (3 items: org metadata, user metadata, org→admin link)

#### Changed

- `auth-handler/dynamo.go`
- `auth-handler/register.go`
- `docs/dynamodb-schema.md`
- `user-handler/list.go`
- `user-handler/model.go`

---

### [fd430c8] — 2026-06-16

**feat: implement user management lambdas with DynamoDB and machine auth**

> auth-handler:
> - Login now validates terminal serial_number for CUSTOMER_OPERATOR role
> using JWT decode (no extra API call) + DynamoDB GSI1 query (AP09)
> - Register writes to DynamoDB (TransactWrite) after Cognito creation;

#### Added

- `.gitignore`
- `auth-handler/dynamo.go`
- `user-handler/README.md`
- `user-handler/delete.go`
- `user-handler/docs/openapi.yaml`
- `user-handler/get.go`
- `user-handler/go.mod`
- `user-handler/go.sum`
- `user-handler/handler.go`
- `user-handler/list.go`
- `user-handler/main.go`
- `user-handler/model.go`
- `user-handler/reset_password.go`
- `user-handler/status.go`
- `user-handler/update.go`

#### Changed

- `auth-handler/README.md`
- `auth-handler/docs/openapi.yaml`
- `auth-handler/go.mod`
- `auth-handler/go.sum`
- `auth-handler/handler.go`
- `auth-handler/login.go`
- `auth-handler/main.go`
- `auth-handler/register.go`
- `auth-handler/register_batch.go`
- `docs/dynamodb-schema.md`

---

### [96f8c43] — 2026-06-14

**fix(ci): use '.' instead of './...' in go build to avoid multi-package error**

#### Changed

- `.github/workflows/deploy-lambdas.yml`

---

### [6332da7] — 2026-06-14

**fix(ci): add id-token permission and move secret to dev environment**

> OIDC requires id-token: write at job level. Secret moved to
> environment scope so it's available when environment: dev is set.

#### Changed

- `.github/workflows/deploy-lambdas.yml`

---

### [63a9e1b] — 2026-06-14

**feat(auth-handler): implement login, register and batch endpoints**

> - login.go: InitiateAuth with USER_PASSWORD_AUTH
> - register.go: AdminCreateUser + AdminSetUserPassword (shared with batch)
> - register_batch.go: multipart/form-data, excelize, chunks of 25
> - handler.go: route dispatch by RouteKey

#### Added

- `auth-handler/docs/openapi.yaml`
- `auth-handler/go.mod`
- `auth-handler/go.sum`
- `auth-handler/handler.go`
- `auth-handler/login.go`
- `auth-handler/main.go`
- `auth-handler/register.go`
- `auth-handler/register_batch.go`
- `auth-handler/rut/normalize.go`
- `auth-handler/rut/validate.go`
- `docs/dynamodb-schema.md`

#### Changed

- `auth-handler/README.md`

---

### [54febc9] — 2026-06-14

**feat: add auth-handler docs and Lambda deploy workflow**

> - auth-handler/README.md: full Go implementation guide (login, register, batch)
> - .github/workflows/deploy-lambdas.yml: auto-deploy on push to develop/main

#### Added

- `.github/workflows/deploy-lambdas.yml`
- `auth-handler/README.md`

---

### [9a71f38] — 2026-06-14

**first commit**

_No file changes detected._

---
