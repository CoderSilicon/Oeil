"""SQLite connection helpers for the Oeil backend."""

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager

from app import config as app_config


def database_path() -> str:
    return app_config.database_path()


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    connection = sqlite3.connect(database_path(), timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        with connection:
            yield connection
    finally:
        connection.close()
