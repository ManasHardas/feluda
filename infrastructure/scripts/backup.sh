#!/usr/bin/env bash
# Logical backup of the application schema with pg_dump (custom format, compressed).
#   OWNER_DATABASE_URL=postgresql://... infrastructure/scripts/backup.sh [output-directory]
# The dump contains streets, house numbers where given and connectivity history: store it encrypted and delete old copies
# in line with docs/PRIVACY.md (suggested: keep 7 daily copies).
set -euo pipefail
: "${OWNER_DATABASE_URL:?set OWNER_DATABASE_URL to the owner connection string}"
out="${1:-.}"
file="$out/watchdog-$(date -u +%Y%m%dT%H%M%SZ).dump"
pg_dump "$OWNER_DATABASE_URL" --schema=nw --format=custom --no-owner --file="$file"
shasum -a 256 "$file"
echo "wrote $file  (restore: pg_restore --clean --if-exists --no-owner -d <target url> $file)"
