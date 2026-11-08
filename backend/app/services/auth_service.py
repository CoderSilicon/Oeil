"""Email/password authentication business logic."""

import sqlite3
import time
import uuid

from app import security
from app.db import sessions, users
from app.db.connection import connect
from app.db.schema import initialize

__all__ = [
    "AuthServiceError",
    "DuplicateEmailError",
    "InvalidCredentialsError",
    "NameRequiredError",
    "issue_session",
    "login",
    "signup",
]


class AuthServiceError(Exception):
    """Base error for email/password authentication."""


class NameRequiredError(AuthServiceError):
    """Raised when signup is missing a name."""


class DuplicateEmailError(AuthServiceError):
    """Raised when a signup email already exists."""


class InvalidCredentialsError(AuthServiceError):
    """Raised when login credentials are invalid."""


def issue_session(user_id: str) -> str:
    initialize()
    token, session_id, expires_at = security.create_session_token(user_id)
    with connect() as connection:
        sessions.create(
            connection,
            session_id=session_id,
            user_id=user_id,
            expires_at=expires_at,
        )
    return token


def signup(*, email: str, password: str, name: str | None) -> sqlite3.Row:
    initialize()
    security.session_secret()
    if name is None:
        raise NameRequiredError()
    user_id = str(uuid.uuid4())
    try:
        with connect() as connection:
            users.create(
                connection,
                user_id=user_id,
                email=email,
                name=name,
                password_hash=security.hash_password(password),
                created_at=int(time.time()),
            )
            user = users.find_by_id(connection, user_id)
    except sqlite3.IntegrityError as error:
        raise DuplicateEmailError() from error
    return user


def login(*, email: str, password: str) -> sqlite3.Row:
    initialize()
    security.session_secret()
    with connect() as connection:
        user = users.find_by_email(connection, email)
    if user is None or not security.verify_password(password, user["password_hash"]):
        raise InvalidCredentialsError()
    return user
