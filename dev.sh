#!/usr/bin/env bash
# =============================================================================
# dev.sh — Levanta Spring Boot con el perfil y entorno elegidos.
#
# Uso:
#   ./dev.sh           → perfil "local" (default)
#   ./dev.sh local     → perfil "local"
#
# Requiere:
#   - .env en la raíz del proyecto (copia .env.example y completa los valores)
#   - Docker Compose corriendo (para PostgreSQL): docker compose up -d
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
PROFILE="${1:-local}"

# --- Validaciones ------------------------------------------------------------

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: no se encontró .env en $SCRIPT_DIR"
  echo "Crea uno copiando el ejemplo:"
  echo "  cp .env.example .env"
  exit 1
fi

if [ ! -f "$SCRIPT_DIR/pom.xml" ]; then
  echo "Error: ejecuta este script desde la raíz del proyecto (donde está pom.xml)"
  exit 1
fi

# --- Cargar .env -------------------------------------------------------------

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# --- Validar credenciales AWS ------------------------------------------------

if [ -z "${AWS_ACCESS_KEY_ID:-}" ] || [ "${AWS_ACCESS_KEY_ID}" = "AKIA..." ]; then
  echo "Error: AWS_ACCESS_KEY_ID no está configurado en .env"
  exit 1
fi

if [ -z "${AWS_SECRET_ACCESS_KEY:-}" ] || [ "${AWS_SECRET_ACCESS_KEY}" = "..." ]; then
  echo "Error: AWS_SECRET_ACCESS_KEY no está configurado en .env"
  exit 1
fi

# --- Arrancar ----------------------------------------------------------------

echo ""
echo "  Perfil  : $PROFILE"
echo "  Region  : ${AWS_REGION:-us-east-1}"
echo "  DB user : ${DB_USERNAME:-parking_user}"
echo ""

SPRING_PROFILES_ACTIVE="$PROFILE" \
  mvn -f "$SCRIPT_DIR/pom.xml" spring-boot:run
