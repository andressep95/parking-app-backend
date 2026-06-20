#!/usr/bin/env bash
# Hook SessionStart — Driven Agent Development
#
# Detects the project stack from build files and injects it as context
# so the agent knows the tech stack from the first token.
#
# Input:  JSON on stdin (ignored — we read the filesystem)
# Output: JSON on stdout with additionalContext

set -uo pipefail
cat > /dev/null  # drain stdin

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
STACK=""

# ── Detect build system & language ─────────────────────────────────────────
if [ -f "$ROOT/pom.xml" ]; then
  JAVA_VER=$(grep -oP '(?<=<java.version>)[^<]+' "$ROOT/pom.xml" 2>/dev/null || \
             grep -oP '(?<=<maven.compiler.source>)[^<]+' "$ROOT/pom.xml" 2>/dev/null || echo "?")
  BOOT_VER=$(grep -oP '(?<=<version>)[^<]+' "$ROOT/pom.xml" 2>/dev/null | head -1 || echo "?")
  STACK="Java ${JAVA_VER} + Maven"
  grep -q "spring-boot" "$ROOT/pom.xml" 2>/dev/null && STACK="$STACK + Spring Boot"
  grep -q "postgresql\|postgres" "$ROOT/pom.xml" 2>/dev/null && STACK="$STACK + PostgreSQL"
  grep -q "mysql" "$ROOT/pom.xml" 2>/dev/null && STACK="$STACK + MySQL"
  grep -q "flyway" "$ROOT/pom.xml" 2>/dev/null && STACK="$STACK + Flyway"
  grep -q "liquibase" "$ROOT/pom.xml" 2>/dev/null && STACK="$STACK + Liquibase"
elif [ -f "$ROOT/build.gradle" ] || [ -f "$ROOT/build.gradle.kts" ]; then
  STACK="Java + Gradle"
  grep -q "spring" "$ROOT/build.gradle"* 2>/dev/null && STACK="$STACK + Spring Boot"
elif [ -f "$ROOT/package.json" ]; then
  STACK="Node.js"
  grep -q "typescript" "$ROOT/package.json" 2>/dev/null && STACK="TypeScript + Node.js"
  grep -q "next" "$ROOT/package.json" 2>/dev/null && STACK="$STACK + Next.js"
  grep -q "react" "$ROOT/package.json" 2>/dev/null && STACK="$STACK + React"
  grep -q "express" "$ROOT/package.json" 2>/dev/null && STACK="$STACK + Express"
elif [ -f "$ROOT/Cargo.toml" ]; then
  STACK="Rust + Cargo"
elif [ -f "$ROOT/go.mod" ]; then
  STACK="Go"
elif [ -f "$ROOT/requirements.txt" ] || [ -f "$ROOT/pyproject.toml" ]; then
  STACK="Python"
  [ -f "$ROOT/pyproject.toml" ] && grep -q "fastapi\|django\|flask" "$ROOT/pyproject.toml" 2>/dev/null && \
    STACK="$STACK + $(grep -oE 'fastapi|django|flask' "$ROOT/pyproject.toml" | head -1)"
fi

# ── Detect infra ───────────────────────────────────────────────────────────
[ -f "$ROOT/Dockerfile" ] && STACK="$STACK + Docker"
[ -f "$ROOT/docker-compose.yml" ] || [ -f "$ROOT/docker-compose.yaml" ] && STACK="$STACK + Compose"
[ -d "$ROOT/terraform" ] || [ -f "$ROOT/main.tf" ] && STACK="$STACK + Terraform"
[ -f "$ROOT/serverless.yml" ] && STACK="$STACK + Serverless"

# ── Detect test runner ─────────────────────────────────────────────────────
BUILD_CMD="?"
TEST_CMD="?"
if [ -f "$ROOT/pom.xml" ]; then
  BUILD_CMD="mvn package"
  TEST_CMD="mvn test"
elif [ -f "$ROOT/build.gradle" ] || [ -f "$ROOT/build.gradle.kts" ]; then
  BUILD_CMD="./gradlew build"
  TEST_CMD="./gradlew test"
elif [ -f "$ROOT/package.json" ]; then
  BUILD_CMD="npm run build"
  TEST_CMD="npm test"
elif [ -f "$ROOT/Cargo.toml" ]; then
  BUILD_CMD="cargo build"
  TEST_CMD="cargo test"
elif [ -f "$ROOT/go.mod" ]; then
  BUILD_CMD="go build ./..."
  TEST_CMD="go test ./..."
fi

# ── Build context ──────────────────────────────────────────────────────────
if [ -z "$STACK" ]; then
  echo '{}'
  exit 0
fi

CTX="## Project Stack (auto-detected)
- Stack: ${STACK}
- Build: \`${BUILD_CMD}\`
- Test: \`${TEST_CMD}\`

Always query memory before implementing. Use the skill that matches the task."

python3 -c "
import json, sys
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'SessionStart',
        'additionalContext': sys.stdin.read()
    }
}))
" <<< "$CTX"
