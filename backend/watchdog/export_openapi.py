"""Write the OpenAPI contract to shared/api-contracts/openapi.json.

    python -m watchdog.export_openapi           # write
    python -m watchdog.export_openapi --check   # fail if the committed file is out of date (used in CI)

No database or network is needed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from .main import create_app
from .settings import Settings

TARGET = Path(__file__).resolve().parents[2] / "shared" / "api-contracts" / "openapi.json"


def render() -> str:
    settings = Settings(_env_file=None, environment="test", background_worker_enabled=False)
    return json.dumps(create_app(settings).openapi(), indent=2, sort_keys=True) + "\n"


def main() -> int:
    text = render()
    if "--check" in sys.argv:
        if not TARGET.exists() or TARGET.read_text(encoding="utf-8") != text:
            print(f"{TARGET} is out of date. Run `make openapi` and commit the result.")
            return 1
        print("API contract is up to date.")
        return 0
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(text, encoding="utf-8")
    print(f"wrote {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
