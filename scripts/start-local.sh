#!/usr/bin/env bash
# Start local Postgres (Docker) + Bengali LKC API
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash "$ROOT/scripts/ensure-db.sh"

set -a
# shellcheck disable=SC1091
source .env
set +a

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT:-5433}/bengali_lkc}"
export DB_USERNAME="${DB_USERNAME:-${POSTGRES_USER:-lkc_admin}}"
export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-lkc_secure_change_me}}"

echo "Starting Spring Boot on :8080 (Flyway will auto-migrate)..."
./gradlew bootRun --args='--spring.profiles.active=local'
