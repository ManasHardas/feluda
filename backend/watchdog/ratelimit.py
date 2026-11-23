"""Rate limiting.

Two layers:
* an in-process sliding window (fast, used for unauthenticated endpoints, keyed by client address);
* database fixed-window counters (shared by all processes, survive restarts), used for the limits
  that protect the detection engine from a misbehaving device or household.
"""

from __future__ import annotations

import threading
from collections import defaultdict, deque
from datetime import datetime, timedelta


class SlidingWindowLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window_s: int, now: datetime) -> int:
        """Record one hit. Returns 0 when allowed, otherwise seconds until the next slot frees up."""
        stamp = now.timestamp()
        with self._lock:
            hits = self._hits[key]
            while hits and hits[0] <= stamp - window_s:
                hits.popleft()
            if len(hits) >= limit:
                return max(1, int(hits[0] + window_s - stamp) + 1)
            hits.append(stamp)
            if len(self._hits) > 50_000:  # bound memory under a flood of distinct keys
                for stale in [k for k, v in self._hits.items() if not v or v[-1] <= stamp - 3600]:
                    del self._hits[stale]
            return 0

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


def _window_start(now: datetime, window_s: int) -> datetime:
    epoch = int(now.timestamp())
    return datetime.fromtimestamp(epoch - epoch % window_s, tz=now.tzinfo)


def consume(conn, bucket: str, limit: int, window_s: int, now: datetime, amount: int = 1) -> tuple[int, int]:
    """Atomically take up to `amount` units from a fixed window.

    Returns (granted, retry_after_seconds). granted < amount means the limit was reached.
    """
    start = _window_start(now, window_s)
    row = conn.execute(
        "INSERT INTO nw.rate_limit_counters (bucket, window_start, count) VALUES (%s, %s, 0)"
        " ON CONFLICT (bucket, window_start) DO UPDATE SET count = nw.rate_limit_counters.count"
        " RETURNING count",
        [bucket, start],
    ).fetchone()
    used = row["count"]
    granted = max(0, min(amount, limit - used))
    if granted:
        conn.execute(
            "UPDATE nw.rate_limit_counters SET count = count + %s WHERE bucket = %s AND window_start = %s",
            [granted, bucket, start],
        )
    retry_after = int((start + timedelta(seconds=window_s) - now).total_seconds()) + 1 if granted < amount else 0
    return granted, retry_after
