"""Authentication database schema initialization."""

from app.db.connection import connect


def initialize() -> None:
    with connect() as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS auth_users (
                id TEXT PRIMARY KEY NOT NULL,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                password_hash TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS oauth_accounts (
                provider TEXT NOT NULL,
                provider_user_id TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
                PRIMARY KEY (provider, provider_user_id)
            );
            CREATE TABLE IF NOT EXISTS auth_sessions (
                id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS oauth_states (
                state TEXT PRIMARY KEY NOT NULL,
                provider TEXT NOT NULL,
                redirect_uri TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            );
            """
        )
