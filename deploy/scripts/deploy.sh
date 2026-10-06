#!/usr/bin/env bash
# Deploys one part of the stack at a release tag, keeping all database data.
#
#   deploy.sh backend  prod-1.2.0
#   deploy.sh frontend prod-1.2.0
#
# Steps: download the image GitHub Actions built for the tag -> back up the database (backend
# only) -> switch that one service to the new image -> wait for its health check -> on failure
# switch back to the previous tag automatically.
# Deploying an older tag is the manual rollback.
#
# Images are never built here (the small server does not have the memory for it). For a backend
# tag, the deploy/ folder of that tag is exported to a temporary folder, and a successful deploy
# refreshes /opt/bengali-lkc/deploy from it with rsync, which replaces files atomically, so this
# running script is never modified mid-deploy.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="${1:-}"
TAG="${2:-}"
[[ "$SERVICE" == "backend" || "$SERVICE" == "frontend" ]] || die "usage: deploy.sh <backend|frontend> <prod-tag>"
[[ "$TAG" =~ ^prod-[A-Za-z0-9._-]+$ ]] || die "tag must look like prod-1.0.0 (got '$TAG')"

REGISTRY="$(env_get IMAGE_REGISTRY)"
[[ -n "$REGISTRY" ]] || die "set IMAGE_REGISTRY in $ENV_FILE (e.g. ghcr.io/sazzadkhan20)"

exec 9>"$LKC_ROOT/.deploy.lock"
flock -w 1800 9 || die "another deployment is still running"

REPOSITORY="$REGISTRY/bengali-lkc-$SERVICE"
IMAGE="$REPOSITORY:$TAG"
KEY="$(echo "$SERVICE" | tr '[:lower:]' '[:upper:]')_TAG"
PREVIOUS="$(release_get "$KEY")"

log "Deploying $SERVICE $TAG (currently: ${PREVIOUS:-nothing})"

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
if [[ "$SERVICE" == "backend" ]]; then
  SRC="$LKC_ROOT/backend"
  log "Fetching deployment files for $TAG"
  git -C "$SRC" fetch --force --tags origin
  git -C "$SRC" rev-parse --verify --quiet "refs/tags/$TAG" > /dev/null || die "tag $TAG not found in the backend repository"
  if git -C "$SRC" cat-file -e "refs/tags/$TAG:deploy" 2>/dev/null; then
    git -C "$SRC" archive "refs/tags/$TAG" deploy | tar -x -C "$BUILD_DIR"
  fi
fi

if docker image inspect "$IMAGE" > /dev/null 2>&1; then
  log "Image $IMAGE already present; reusing it"
else
  log "Downloading $IMAGE"
  docker pull "$IMAGE" || die "could not pull $IMAGE. Check that the GitHub Actions 'image' job succeeded and that this server is logged in to ghcr.io (DEPLOYMENT.md, Part 4.7)"
fi

if [[ "$SERVICE" == "backend" && -n "$(dc ps -q postgres 2>/dev/null)" ]]; then
  bash "$DEPLOY_DIR/scripts/backup-db.sh" "before-$TAG"
fi

release_set "$KEY" "$TAG"
dc up -d postgres
dc up -d --no-deps "$SERVICE"

if wait_healthy "$SERVICE"; then
  log "$SERVICE $TAG is healthy"
  if [[ "$SERVICE" == "backend" && -d "$BUILD_DIR/deploy" ]]; then
    log "Updating deployment files from $TAG"
    rsync -a --delete "$BUILD_DIR/deploy/" "$DEPLOY_DIR/"
  fi
else
  log "$SERVICE $TAG failed its health check; recent logs:"
  dc logs --tail 80 "$SERVICE" || true
  if [[ -n "$PREVIOUS" && "$PREVIOUS" != "$TAG" ]]; then
    log "Rolling back $SERVICE to $PREVIOUS"
    release_set "$KEY" "$PREVIOUS"
    dc up -d --no-deps "$SERVICE"
    wait_healthy "$SERVICE" || log "WARNING: $PREVIOUS is not healthy either; check 'status.sh'"
    echo "$(date -Is) $SERVICE $TAG FAILED -> rolled back to $PREVIOUS" >> "$HISTORY_FILE"
  else
    release_set "$KEY" ""
    echo "$(date -Is) $SERVICE $TAG FAILED (no previous version)" >> "$HISTORY_FILE"
  fi
  exit 1
fi

# The public entry point starts once both parts exist and HTTPS is set up.
if [[ -n "$(release_get BACKEND_TAG)" && -n "$(release_get FRONTEND_TAG)" ]]; then
  if cert_exists; then
    dc up -d nginx certbot
    dc exec -T nginx nginx -s reload > /dev/null 2>&1 || true
  else
    log "HTTPS certificate not found yet; run scripts/init-https.sh once to go live"
  fi
fi

echo "$(date -Is) $SERVICE $TAG OK (previous: ${PREVIOUS:-none})" >> "$HISTORY_FILE"

# Keep the three newest images of this service for quick rollbacks.
docker images "$REPOSITORY" --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' \
  | sort -r | tail -n +4 | cut -d'|' -f2 \
  | grep -vE ":(${TAG}|${PREVIOUS:-__none__})$" \
  | xargs -r docker rmi > /dev/null 2>&1 || true
docker image prune -f > /dev/null 2>&1 || true

log "Done. Live tags: backend=$(release_get BACKEND_TAG) frontend=$(release_get FRONTEND_TAG)"
