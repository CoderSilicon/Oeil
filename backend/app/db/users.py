"""User table database operations."""

import sqlite3

__all__ = ["create", "find_by_email", "find_by_id"]


def find_by_email(
    connection: sqlite3.Connection, email: str
) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM auth_users WHERE email = ?", (email,)
    ).fetchone()


def find_by_id(
    connection: sqlite3.Connection, user_id: str | None
) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM auth_users WHERE id = ?", (user_id,)
    ).fetchone()


def create(
    connection: sqlite3.Connection,
    *,
    user_id: str,
    email: str,
    name: str,
    password_hash: str | None,
    created_at: int,
) -> None:
    if password_hash is None:
        connection.execute(
            "INSERT INTO auth_users (id, email, name, created_at) VALUES (?, ?, ?, ?)",
            (user_id, email, name, created_at),
        )
    else:
        connection.execute(
            """
            INSERT INTO auth_users (id, email, name, password_hash, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (user_id, email, name, password_hash, created_at),
        )
