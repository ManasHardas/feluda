#!/usr/bin/env bash
# Checks that a deployed backend answers and can reach its database.
#   infrastructure/scripts/smoke_test.sh https://api.example.org
set -euo pipefail
base="${1:?usage: smoke_test.sh <base url>}"
body="$(curl --silent --show-error --fail --max-time 20 --retry 3 --retry-delay 5 "${base%/}/api/v1/monitoring/health")"
echo "$body"
echo "$body" | grep -q '"database":"ok"' || { echo "database is not healthy" >&2; exit 1; }
code="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 20 "${base%/}/api/v1/households/me")"
[ "$code" = "401" ] || { echo "expected 401 without a token, got $code" >&2; exit 1; }
echo "smoke test passed"
