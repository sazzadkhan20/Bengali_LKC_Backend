#!/usr/bin/env bash
# Forced command for the GitHub Actions SSH key (see DEPLOYMENT.md, "authorized_keys").
# The key can only run "<backend|frontend> <prod-tag>"; anything else is refused.
set -euo pipefail

read -r SERVICE TAG EXTRA <<< "${SSH_ORIGINAL_COMMAND:-}"
if [[ -n "${EXTRA:-}" || ! "${SERVICE:-}" =~ ^(backend|frontend)$ || ! "${TAG:-}" =~ ^prod-[A-Za-z0-9._-]+$ ]]; then
  echo "Refused: expected '<backend|frontend> <prod-tag>'" >&2
  exit 2
fi

exec bash "$(dirname "${BASH_SOURCE[0]}")/deploy.sh" "$SERVICE" "$TAG"
