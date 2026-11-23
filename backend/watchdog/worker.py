"""Standalone maintenance worker.

    python -m watchdog.worker          # loop forever
    python -m watchdog.worker --once   # one pass (for cron or a scheduled job)

Only needed when the API runs with BACKGROUND_WORKER_ENABLED=false, for example on a host that
puts idle web processes to sleep.
"""

from __future__ import annotations

import sys
import time

from .clock import Clock
from .db import Database
from .logging_setup import configure_logging
from .notifications.providers import build_senders
from .pipeline import Pipeline, run_tick_exclusively
from .settings import get_settings


def main() -> int:
    settings = get_settings()
    configure_logging(settings.log_level)
    db = Database(settings.database_url, settings.database_pool_max)
    db.open()
    pipeline = Pipeline(db, build_senders(settings), Clock())
    try:
        if "--once" in sys.argv:
            run_tick_exclusively(pipeline)
            return 0
        while True:
            run_tick_exclusively(pipeline)
            time.sleep(settings.tick_interval_seconds)
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
