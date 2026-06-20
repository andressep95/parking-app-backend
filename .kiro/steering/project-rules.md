# Kernel — Skill-Driven Protocol

## Rules

1. **Every task is executed through a skill.** No skill → no action. State `"Skill gap detected."` if none applies.
2. **Load the SKILL.md before generating output.** The name is not the protocol. The file is.
3. **Memory and stack are injected automatically.** Hooks handle Chroma queries, commit validation, and /clear reminders. Do not duplicate that work.

## Execution Flow

```
task → [hook injects memory + skill hint] → load skill → execute → commit → /clear
```

## Available Skills

| Skill | Description | File |
|-------|-------------|------|
| `311-frameworks-spring-jdbc` | Use when you need to write or review programmatic JDBC with Spring — including JdbcClient (Spring Framework 7+) as the default API, JdbcTemplate only where batch/streaming APIs require JdbcOperations, NamedParameterJdbcTemplate for legacy named-param code, parameterized SQL, RowMapper mapping to records, batch operations, transactions, safe handling of generated keys, DataAccessException handling, read-only transactions, streaming large result sets, and @JdbcTest slice testing. This should trigger for requests such as Review Java code for Spring JDBC (JdbcTemplate, JdbcClient, NamedParameterJdbcTemplate); Apply best practices for Spring JDBC data access in Java code; Detect and fix SQL injection risks in JDBC code; Improve transaction boundaries or exception handling for JDBC operations. Part of cursor-rules-java project | [SKILL.md](.agents/skills/311-frameworks-spring-jdbc/SKILL.md) |
| `commit` | Enforces professional git commits using the Conventional Commits specification. Trigger: Before any git commit or when requested to commit changes. | [SKILL.md](.agents/skills/commit/SKILL.md) |
| `endpoint-trace` | Generates code-level trace documents for each HTTP endpoint, mapping the full call chain from the controller inward through every component it touches (services, repositories, AWS clients, shared utilities). Output lives in docs/traces/ and is meant for developers navigating the codebase, not end users. Trigger: When documenting a new endpoint at the code level, auditing dependencies of an existing endpoint, or creating an endpoint-to-component map. | [SKILL.md](.agents/skills/endpoint-trace/SKILL.md) |
| `feature-docs` | When a feature is marked as complete in api/openapi.yaml, generates a Markdown usage-flow document in docs/features/ explaining how to use it end-to-end. Trigger: After updating api/openapi.yaml with a completed endpoint. | [SKILL.md](.agents/skills/feature-docs/SKILL.md) |
| `find-skills` | Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill. | [SKILL.md](.agents/skills/find-skills/SKILL.md) |
| `kafka-development-practices` | Applies Kafka best practices for JVM backends written in Java. Framework-agnostic: works with plain kafka-clients, Quarkus SmallRye, Spring Kafka, or Micronaut Kafka without any changes to the core rules. Trigger: When producing, consuming, or processing Kafka events in Java. | [SKILL.md](.agents/skills/kafka-development-practices/SKILL.md) |
| `openapi` | Keeps api/openapi.yaml in sync with the Spring controllers in src/main/java. Trigger: After adding, modifying, or deleting any HTTP endpoint or changing a request/response schema. | [SKILL.md](.agents/skills/openapi/SKILL.md) |
| `skill-creator` | Creates new AI agent skills following the project skill spec. Trigger: When user asks to create a new skill, add agent instructions, or document patterns for AI reuse. | [SKILL.md](.agents/skills/skill-creator/SKILL.md) |
| `skill-sync` | Keeps the Available Skills and Auto-Invoke Skills tables in sync with skill metadata after any skill is created or modified. Detects CLAUDE.md and .kiro/steering/project-rules.md by file existence and updates both. Trigger: After creating or modifying any SKILL.md file. | [SKILL.md](.agents/skills/skill-sync/SKILL.md) |

## Auto-Invoke Skills

When performing these actions, ALWAYS load the corresponding skill FIRST:

| Action | Skill |
|--------|-------|
| Add Kafka Streams topology | `kafka-development-practices` |
| Add a slash command | `skill-creator` |
| Add agent instructions | `skill-creator` |
| Adding a new endpoint | `openapi` |
| After creating or modifying a skill | `skill-sync` |
| After updating api/openapi.yaml with a completed endpoint | `feature-docs` |
| Agregar o modificar un endpoint | `openapi` |
| Auto-invoke table is out of sync | `skill-sync` |
| Before any data model decision | `311-frameworks-spring-jdbc` |
| Before any data query decision | `311-frameworks-spring-jdbc` |
| Before any git commit | `commit` |
| Buscar un skill para una tarea | `find-skills` |
| Cambiar esquema de request o response | `openapi` |
| Changing API responses | `openapi` |
| Changing request or response schema | `openapi` |
| Commitear, guardar cambios en git | `commit` |
| Configure Kafka client | `kafka-development-practices` |
| Create a new skill | `skill-creator` |
| Creating a git commit | `commit` |
| Dead letter queue Kafka | `kafka-development-practices` |
| Deleting an endpoint | `openapi` |
| Después de crear o modificar un skill | `skill-sync` |
| Document a pattern for AI reuse | `skill-creator` |
| Documentar funcionalidad terminada | `feature-docs` |
| Documenting a completed feature | `feature-docs` |
| Encontrar o instalar skills disponibles | `find-skills` |
| Escribir mensaje de commit | `commit` |
| Feature is ready and needs usage documentation | `feature-docs` |
| Find a skill for a task | `find-skills` |
| Generar documentación de una feature completa | `feature-docs` |
| Hacer commit de los cambios | `commit` |
| Handle Kafka message serialization | `kafka-development-practices` |
| Implement Kafka consumer in Java | `kafka-development-practices` |
| Implement Kafka producer in Java | `kafka-development-practices` |
| Install a new skill | `find-skills` |
| Kafka error handling Java | `kafka-development-practices` |
| La feature está lista, necesita documentación | `feature-docs` |
| Modifying a Spring controller | `openapi` |
| Search for available skills | `find-skills` |
| Sincronizar especificación openapi | `openapi` |
| Sincronizar tabla de skills | `skill-sync` |
| Stage and commit changes | `commit` |
| Teach the agent how to do X | `skill-creator` |
| Test Kafka topology | `kafka-development-practices` |
| Write a commit message | `commit` |
| crear endpoint REST, API HTTP | `endpoint-trace` |
| create code-level endpoint doc | `endpoint-trace` |
| document endpoint code trace | `endpoint-trace` |
| documentar endpoint, trazar dependencias | `endpoint-trace` |
| map endpoint call chain | `endpoint-trace` |
| mapear cadena de llamadas de un endpoint | `endpoint-trace` |
| trace endpoint dependencies | `endpoint-trace` |

## Architecture Decision Records

| ADR | Decision |
|-----|----------|
| TODO | Add your ADRs here |
