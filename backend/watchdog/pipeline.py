"""Glue between ingestion, correlation, notifications and housekeeping.

There is no message broker: PostgreSQL is the queue. New events are marked by correlated_at IS NULL
and pending notifications by status = 'pending'. The same functions run right after an upload
(event-driven) and from the periodic tick (escalation, expiry, retries, retention).
"""

from __future__ import annotations

import logging
import threading
from datetime import datetime
from uuid import UUID

from . import metrics
from .clock import Clock
from .correlation import service as correlation
from .db import Database
from .notifications import service as notifications
from .notifications.providers import PushSender
from .services import retention

log = logging.getLogger(__name__)


class Pipeline:
    def __init__(self, db: Database, senders: dict[str, PushSender], clock: Clock) -> None:
        self.db = db
        self.senders = senders
        self.clock = clock
        self._last_retention: datetime | None = None

    def process_neighborhood(self, neighborhood_id: UUID, now: datetime | None = None) -> list[correlation.IncidentChange]:
        now = now or self.clock.now()
        changes = correlation.correlate_neighborhood(self.db, neighborhood_id, now)
        notifications.plan(self.db, neighborhood_id, now)
        return changes

    def deliver(self, now: datetime | None = None) -> dict[str, int]:
        return notifications.dispatch(self.db, self.senders, now or self.clock.now())

    def tick(self, now: datetime | None = None) -> None:
        """One maintenance pass over every neighborhood that has pending events or open incidents."""
        now = now or self.clock.now()
        for neighborhood_id in correlation.neighborhoods_needing_work(self.db):
            try:
                self.process_neighborhood(neighborhood_id, now)
            except Exception:
                metrics.incr("tick_errors")
                log.exception("correlation failed", extra={"neighborhood_id": str(neighborhood_id)})
        self.deliver(now)
        if self._last_retention is None or (now - self._last_retention).total_seconds() >= 6 * 3600:
            self._last_retention = now
            retention.purge(self.db, now)


class BackgroundWorker:
    """Runs Pipeline.tick() on an interval in a daemon thread of the API process.

    A PostgreSQL advisory lock makes sure only one process ticks at a time, so several API
    instances (or the standalone `python -m watchdog.worker`) can run side by side.
    """

    def __init__(self, pipeline: Pipeline, interval_s: int) -> None:
        self._pipeline = pipeline
        self._interval = interval_s
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="watchdog-worker", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=10)

    def _loop(self) -> None:
        while not self._stop.wait(self._interval):
            run_tick_exclusively(self._pipeline)


def run_tick_exclusively(pipeline: Pipeline) -> bool:
    try:
        with pipeline.db.service() as conn:
            got = conn.execute("SELECT pg_try_advisory_xact_lock(727274002) AS got").fetchone()["got"]
            if not got:
                return False
            pipeline.tick()
            metrics.incr("ticks")
        return True
    except Exception:
        metrics.incr("tick_errors")
        log.exception("tick failed")
        return False
