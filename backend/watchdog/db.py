"""Database access.

Every transaction runs under one of three restricted roles (see migrations/0001_initial_schema.sql)
and never as the login/owner role. For the resident role the signed-in user id is passed to
PostgreSQL so row-level security can enforce what that person may see.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Literal
from uuid import UUID

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

Role = Literal["nw_api", "nw_correlation", "nw_service"]
Conn = psycopg.Connection


class Database:
    def __init__(self, url: str, max_size: int = 5) -> None:
        self._pool = ConnectionPool(
            url,
            min_size=1,
            max_size=max_size,
            # prepare_threshold=None keeps this compatible with transaction-mode poolers (Supabase/pgbouncer).
            kwargs={"row_factory": dict_row, "prepare_threshold": None, "autocommit": False},
            open=False,
        )

    def open(self) -> None:
        self._pool.open(wait=True, timeout=30)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def tx(self, role: Role, user_id: UUID | None = None) -> Iterator[Conn]:
        """One transaction under `role`. Commits on success, rolls back on any exception."""
        with self._pool.connection() as conn, conn.transaction():
            conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
            conn.execute("SELECT set_config('app.user_id', %s, true)", [str(user_id) if user_id else ""])
            yield conn

    def api(self, user_id: UUID):
        return self.tx("nw_api", user_id)

    def service(self):
        return self.tx("nw_service")

    def correlation(self):
        return self.tx("nw_correlation")

    def ping(self) -> bool:
        try:
            with self._pool.connection(timeout=3) as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:
            return False
