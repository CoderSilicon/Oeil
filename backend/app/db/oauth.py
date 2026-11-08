"""OAuth account and state database operations."""

import sqlite3

__all__ = [
    "begin_immediate",
    "create_account",
    "create_state",
    "delete_expired_states",
    "delete_state",
    "find_account",
    "find_state",
]


def find_account(
    connection: sqlite3.Connection,
    provider: str,
    provider_user_id: str,
) -> sqlite3.Row | None:
    return connection.execute(
        """
        SELECT auth_users.* FROM oauth_accounts
        JOIN auth_users ON auth_users.id = oauth_accounts.user_id
        WHERE provider = ? AND provider_user_id = ?
        """,
        (provider, provider_user_id),
    ).fetchone()


def create_account(
    connection: sqlite3.Connection,
    *,
    provider: str,
    provider_user_id: str,
    user_id: str,
) -> None:
    connection.execute(
        "INSERT INTO oauth_accounts (provider, provider_user_id, user_id) VALUES (?, ?, ?)",
        (provider, provider_user_id, user_id),
    )


def delete_expired_states(connection: sqlite3.Connection, now: int) -> None:
    connection.execute(
        "DELETE FROM oauth_states WHERE expires_at <= ?", (now,)
    )


def create_state(
    connection: sqlite3.Connection,
    *,
    state: str,
    provider: str,
    redirect_uri: str,
    expires_at: int,
) -> None:
    connection.execute(
        "INSERT INTO oauth_states (state, provider, redirect_uri, expires_at) VALUES (?, ?, ?, ?)",
        (state, provider, redirect_uri, expires_at),
    )


def find_state(
    connection: sqlite3.Connection,
    state: str,
    provider: str,
) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM oauth_states WHERE state = ? AND provider = ?",
        (state, provider),
    ).fetchone()


def delete_state(connection: sqlite3.Connection, state: str) -> None:
    connection.execute(
        "DELETE FROM oauth_states WHERE state = ?", (state,)
    )


def begin_immediate(connection: sqlite3.Connection) -> None:
    connection.execute("BEGIN IMMEDIATE")
