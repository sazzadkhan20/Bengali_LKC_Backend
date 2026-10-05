#!/usr/bin/env bash
# Writes a compressed PostgreSQL dump to /opt/bengali-lkc/backups and keeps 14 days of them.
#   backup-db.sh            (daily cron)
#   backup-db.sh my-label   (named, e.g. before a deploy)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LABEL="${1:-daily}"
mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/lkc-$(date +%Y%m%d-%H%M%S)-${LABEL//[^A-Za-z0-9._-]/_}.dump"

log "Backing up database to $FILE"
dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=9' > "$FILE.partial"
mv "$FILE.partial" "$FILE"
[[ -s "$FILE" ]] || die "backup is empty"

find "$BACKUP_DIR" -name 'lkc-*.dump' -mtime +14 -delete
log "Backup done ($(du -h "$FILE" | cut -f1))"
