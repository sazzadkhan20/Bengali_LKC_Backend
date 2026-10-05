# Shared helpers for the deploy scripts. Source it; do not run it.
set -euo pipefail

LKC_ROOT="${LKC_ROOT:-/opt/bengali-lkc}"
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$LKC_ROOT/.env"
RELEASE_FILE="$LKC_ROOT/release.env"
BACKUP_DIR="$LKC_ROOT/backups"
HISTORY_FILE="$LKC_ROOT/deploy-history.log"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || die "$ENV_FILE is missing. Copy deploy/.env.example there and fill it in."
touch "$RELEASE_FILE"

dc() {
  docker compose \
    --project-directory "$DEPLOY_DIR" \
    -f "$DEPLOY_DIR/compose.prod.yaml" \
    --env-file "$ENV_FILE" \
    --env-file "$RELEASE_FILE" \
    "$@"
}

release_get() { grep -E "^$1=" "$RELEASE_FILE" | tail -n1 | cut -d= -f2- || true; }

release_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -vE "^$key=" "$RELEASE_FILE" > "$tmp" || true
  echo "$key=$value" >> "$tmp"
  mv "$tmp" "$RELEASE_FILE"
}

# Waits until the service's container health check reports healthy.
wait_healthy() {
  local service="$1" timeout="${2:-420}" waited=0 id status restarts
  while (( waited < timeout )); do
    id="$(dc ps -aq "$service" 2>/dev/null || true)"
    if [[ -n "$id" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)"
      restarts="$(docker inspect --format '{{.RestartCount}}' "$id" 2>/dev/null || echo 0)"
      [[ "$status" == "healthy" ]] && return 0
      [[ "$status" == "unhealthy" || "$status" == "exited" ]] && return 1
      # A crash loop never reaches "unhealthy"; give up after repeated restarts.
      (( restarts >= 2 )) && return 1
    fi
    sleep 5
    waited=$(( waited + 5 ))
  done
  return 1
}

cert_exists() {
  docker run --rm -v bengali-lkc-letsencrypt:/etc/letsencrypt:ro alpine \
    test -f /etc/letsencrypt/live/lkc/fullchain.pem 2>/dev/null
}
