#!/usr/bin/env bash
# Runs one app's real networking code against a locally running development backend.
#
#   make docker-up migrate seed      # once
#   make live-backend                # in another terminal
#   scripts/live_contract.sh ios     # or: android
#
# The fixture approves a fresh address, creates an invitation for it and replays a correlated
# disruption, then hands those values to the app's live test through environment variables.
set -euo pipefail
platform="${1:?usage: live_contract.sh ios|android}"
root="$(cd "$(dirname "$0")/.." && pwd)"
api="${WATCHDOG_LIVE_API:-http://localhost:8000}"

export ENVIRONMENT=development
export DATABASE_URL="${DATABASE_URL:-postgresql://watchdog:watchdog_local_dev_only@localhost:54329/watchdog}"
export DEV_AUTH_ENABLED=true
export AUTH_JWT_SECRET=local-development-secret-not-for-production-use
export DEV_AUTH_FIXED_CODE="${DEV_AUTH_FIXED_CODE:-424242}"

fixture="$(cd "$root/backend" && uv run python -m watchdog.simulation.live_fixture --platform "$platform" --base-url "$api")"
while IFS='=' read -r key value; do
  export "$key=$value"
done <<< "$fixture"
export WATCHDOG_LIVE_API="$api"

case "$platform" in
  ios)
    cd "$root/apps/ios"
    swift test --filter LiveBackendTests
    ;;
  android)
    cd "$root/apps/android"
    ./gradlew --no-daemon testDebugUnitTest --tests '*LiveBackendTest*' --rerun-tasks
    ;;
  *)
    echo "unknown platform: $platform" >&2
    exit 2
    ;;
esac
