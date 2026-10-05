#!/usr/bin/env bash
# Replaces the live database with a backup. Takes a safety backup first.
#   restore-db.sh /opt/bengali-lkc/backups/lkc-20261005-020000-daily.dump
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

FILE="${1:-}"
[[ -f "$FILE" ]] || die "usage: restore-db.sh <backup .dump file>"

read -r -p "This REPLACES all current data with $FILE. Type RESTORE to continue: " answer
[[ "$answer" == "RESTORE" ]] || die "cancelled"

bash "$DEPLOY_DIR/scripts/backup-db.sh" "before-restore"
log "Stopping the app"
dc stop frontend backend
log "Restoring $FILE"
dc exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < "$FILE"
log "Starting the app"
dc up -d --no-deps backend
wait_healthy backend || die "backend is not healthy after the restore; check 'status.sh'"
dc up -d --no-deps frontend
log "Restore complete"
