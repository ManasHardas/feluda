#!/usr/bin/env bash
# Deploy the backend to Fly.io from a workstation. The CI workflow "Deploy staging" does the same.
#   MIGRATION_DATABASE_URL=postgresql://owner... infrastructure/scripts/deploy.sh staging
# Order matters: migrations first (they are backward compatible by policy), then the new image.
set -euo pipefail
environment="${1:?usage: deploy.sh staging|production}"
case "$environment" in staging|production) ;; *) echo "unknown environment: $environment" >&2; exit 2 ;; esac
: "${MIGRATION_DATABASE_URL:?set MIGRATION_DATABASE_URL to the database owner connection string}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
if [ "$environment" = "production" ]; then
  read -r -p "Deploy commit $(git -C "$root" rev-parse --short HEAD) to PRODUCTION? Type 'production' to continue: " answer
  [ "$answer" = "production" ] || { echo "aborted"; exit 1; }
fi
(cd "$root/backend" && DATABASE_URL="$MIGRATION_DATABASE_URL" uv run python -m watchdog.migrate)
flyctl deploy "$root/backend" --config "$root/infrastructure/deployment/fly.$environment.toml" --remote-only
url="$(grep -E '^\s*PUBLIC_BASE_URL' "$root/infrastructure/deployment/fly.$environment.toml" | sed -E 's/.*"(.*)".*/\1/')"
"$root/infrastructure/scripts/smoke_test.sh" "$url"
