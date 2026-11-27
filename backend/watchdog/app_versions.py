"""Which app versions the server still accepts.

People rarely update apps they installed outside a store, so the server, not the app, decides when a
version is too old. Every app request carries `X-App-Version: <platform>/<major.minor.patch>`. A
version below the configured minimum for its platform is refused with 426 `update_required`, and the
app shows an "update required" screen instead of sending data the server can no longer interpret.
`GET /api/v1/app/compatibility` answers the same question without a credential, for app start-up.

The minimums are settings (MIN_APP_VERSION_IOS, MIN_APP_VERSION_ANDROID); empty means no minimum.
Raise them only when a backend change cannot serve the older version, and only after the newer
version has been distributed.
"""

from __future__ import annotations

import re

from .enums import Platform

HEADER = "X-App-Version"
_VERSION = re.compile(r"^(\d{1,4})\.(\d{1,4})\.(\d{1,4})$")
_HEADER = re.compile(r"^(ios|android)/(\d{1,4}\.\d{1,4}\.\d{1,4})(?:\s.*)?$")

# Requests to these paths are answered whatever the app version: the compatibility check itself,
# monitoring, and the operator's administration API.
EXEMPT_PREFIXES = ("/api/v1/app/", "/api/v1/monitoring/", "/api/v1/admin/")


def parse_version(value: str) -> tuple[int, int, int] | None:
    match = _VERSION.match(value.strip())
    return tuple(int(part) for part in match.groups()) if match else None  # type: ignore[return-value]


def parse_header(value: str) -> tuple[Platform, tuple[int, int, int]] | None:
    """`ios/1.2.3` or `android/1.2.3 (45)` -> (platform, version). Anything else -> None (not enforced)."""
    match = _HEADER.match(value.strip())
    if not match:
        return None
    version = parse_version(match.group(2))
    return (Platform(match.group(1)), version) if version else None


def is_below(version: tuple[int, int, int], minimum: str) -> bool:
    floor = parse_version(minimum) if minimum else None
    return floor is not None and version < floor
