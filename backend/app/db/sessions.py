"""Session table database operations."""

import sqlite3

__all__ = ["create", "find_by_id", "revoke"]


def create(
    connection: sqlite3.Connection,
    *,
    session_id: str,
    user_id: str,
    expires_at: int,
) -> None:
    connection.execute(
        "INSERT INTO auth_sessions (id, user_id, expires_at) VALUES (?, ?, ?)",
        (session_id, user_id, expires_at),
    )


def find_by_id(
    connection: sqlite3.Connection,
    session_id: str | None,
    user_id: str | None,
) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM auth_sessions WHERE id = ? AND user_id = ?",
        (session_id, user_id),
    ).fetchone()


def revoke(
    connection: sqlite3.Connection,
    session_id: str,
    user_id: str,
) -> None:
    connection.execute(
        "UPDATE auth_sessions SET revoked = 1 WHERE id = ? AND user_id = ?",
        (session_id, user_id),
    )
