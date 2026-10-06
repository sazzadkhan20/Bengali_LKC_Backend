#!/usr/bin/env bash
# Shows what is running, which tags are live, recent deploys, backups and disk usage.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

echo "== Live release";  cat "$RELEASE_FILE"; echo
echo "== Containers";    dc ps; echo
echo "== Recent deploys"; tail -n 10 "$HISTORY_FILE" 2>/dev/null || echo "(none yet)"; echo
echo "== Latest backups"; ls -lht "$BACKUP_DIR" 2>/dev/null | head -n 6 || echo "(none yet)"; echo
echo "== Memory";        free -h; echo
echo "== Disk";          df -h / | tail -n 1
