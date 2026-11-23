"""Forward-only SQL migration runner.

Usage: python -m watchdog.migrate [--check]

Applies backend/migrations/NNNN_name.sql in order, each in its own transaction, and records the
file name and SHA-256 in nw.schema_migrations. An already-applied file whose content changed is an
error: migrations are immutable once applied. Must be run with a role that owns the schema.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

import psycopg

from .settings import get_settings

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"


def _files() -> list[Path]:
    return sorted(p for p in MIGRATIONS_DIR.glob("[0-9][0-9][0-9][0-9]_*.sql"))


def pending(conn: psycopg.Connection) -> list[Path]:
    conn.execute("CREATE SCHEMA IF NOT EXISTS nw")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS nw.schema_migrations ("
        " filename text PRIMARY KEY, sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())"
    )
    applied = {row[0]: row[1] for row in conn.execute("SELECT filename, sha256 FROM nw.schema_migrations")}
    todo = []
    for path in _files():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in applied:
            if applied[path.name] != digest:
                raise RuntimeError(f"Migration {path.name} was modified after it was applied")
            continue
        todo.append(path)
    return todo


def run(database_url: str, check_only: bool = False) -> list[str]:
    done: list[str] = []
    with psycopg.connect(database_url, autocommit=True) as conn:
        conn.execute("SELECT pg_advisory_lock(727274001)")
        try:
            todo = pending(conn)
            if check_only:
                return [p.name for p in todo]
            for path in todo:
                with conn.transaction():
                    conn.execute(path.read_text(encoding="utf-8"))  # noqa: S608 - trusted repository file
                    conn.execute(
                        "INSERT INTO nw.schema_migrations (filename, sha256) VALUES (%s, %s)",
                        [path.name, hashlib.sha256(path.read_bytes()).hexdigest()],
                    )
                done.append(path.name)
        finally:
            conn.execute("SELECT pg_advisory_unlock(727274001)")
    return done


def main() -> int:
    check_only = "--check" in sys.argv
    names = run(get_settings().database_url, check_only=check_only)
    if check_only:
        print("pending migrations:" if names else "no pending migrations", *names, sep="\n  " if names else "")
        return 1 if names else 0
    print("applied:" if names else "database is up to date", *names, sep="\n  " if names else "")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
