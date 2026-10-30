import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager

from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field, field_validator

router = APIRouter(prefix="/auth", tags=["authentication"])
bearer = HTTPBearer(auto_error=False)
SESSION_TTL_SECONDS = 60 * 60 * 24 * 30
PASSWORD_ROUNDS = 310_000
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


class Credentials(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=256)
    name: str | None = Field(default=None, min_length=1, max_length=80)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        normalized = value.strip().casefold()
        if not EMAIL_PATTERN.fullmatch(normalized):
            raise ValueError("Enter a valid email address.")
        return normalized

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("Name cannot be blank.")
        return normalized


def _database_path() -> str:
    return os.getenv("OEIL_AUTH_DB_PATH", "oeil-auth.sqlite3")


@contextmanager
def _connect():
    connection = sqlite3.connect(_database_path(), timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def _initialize() -> None:
    with _connect() as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS auth_users (
                id TEXT PRIMARY KEY NOT NULL,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                password_hash TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS auth_sessions (
                id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0
            );
            """
        )


def _session_secret() -> bytes:
    secret = os.getenv("OEIL_AUTH_SECRET", "").strip()
    if len(secret) < 32:
        raise HTTPException(
            status_code=503,
            detail="Authentication is not configured. Set OEIL_AUTH_SECRET to a random value of at least 32 characters.",
        )
    return secret.encode("utf-8")


def _hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_ROUNDS
    )
    return "pbkdf2_sha256${}${}${}".format(
        PASSWORD_ROUNDS,
        base64.urlsafe_b64encode(salt).decode("ascii"),
        base64.urlsafe_b64encode(digest).decode("ascii"),
    )


def _verify_password(password: str, stored_hash: str | None) -> bool:
    if not stored_hash:
        return False
    try:
        algorithm, rounds, salt, expected = stored_hash.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        actual = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            base64.urlsafe_b64decode(salt),
            int(rounds),
        )
        return hmac.compare_digest(
            actual, base64.urlsafe_b64decode(expected)
        )
    except (ValueError, TypeError):
        return False


def _encode_part(value: dict) -> str:
    return base64.urlsafe_b64encode(
        json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
    ).rstrip(b"=").decode("ascii")


def _issue_session(user_id: str) -> str:
    _initialize()
    secret = _session_secret()
    now = int(time.time())
    session_id = secrets.token_urlsafe(24)
    payload = {"sub": user_id, "jti": session_id, "exp": now + SESSION_TTL_SECONDS}
    unsigned = f"{_encode_part({'alg': 'HS256', 'typ': 'JWT'})}.{_encode_part(payload)}"
    signature = hmac.new(secret, unsigned.encode("ascii"), hashlib.sha256).digest()
    token = f"{unsigned}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode('ascii')}"
    with _connect() as connection:
        connection.execute(
            "INSERT INTO auth_sessions (id, user_id, expires_at) VALUES (?, ?, ?)",
            (session_id, user_id, now + SESSION_TTL_SECONDS),
        )
    return token


def _decode_session(token: str) -> dict:
    secret = _session_secret()
    try:
        header, payload, signature = token.split(".")
        unsigned = f"{header}.{payload}"
        expected = hmac.new(secret, unsigned.encode("ascii"), hashlib.sha256).digest()
        supplied = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
        if not hmac.compare_digest(expected, supplied):
            raise ValueError("Invalid signature")
        decoded = json.loads(
            base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        )
        if decoded.get("exp", 0) <= int(time.time()):
            raise ValueError("Expired session")
        return decoded
    except (ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        raise HTTPException(status_code=401, detail="Invalid or expired session.") from error


def _user_payload(user: sqlite3.Row) -> dict[str, str]:
    return {"id": user["id"], "name": user["name"], "email": user["email"]}


def _auth_response(user: sqlite3.Row) -> dict:
    return {"token": _issue_session(user["id"]), "user": _user_payload(user)}


async def current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> sqlite3.Row:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="Sign in to continue.")
    payload = _decode_session(credentials.credentials)
    _initialize()
    with _connect() as connection:
        session = connection.execute(
            "SELECT * FROM auth_sessions WHERE id = ? AND user_id = ?",
            (payload.get("jti"), payload.get("sub")),
        ).fetchone()
        if (
            session is None
            or session["revoked"]
            or session["expires_at"] <= int(time.time())
        ):
            raise HTTPException(status_code=401, detail="Session has expired.")
        user = connection.execute(
            "SELECT * FROM auth_users WHERE id = ?", (payload.get("sub"),)
        ).fetchone()
    if user is None:
        raise HTTPException(status_code=401, detail="Account no longer exists.")
    return user


@router.post("/signup")
def signup(credentials: Credentials) -> dict:
    _initialize()
    _session_secret()
    if credentials.name is None:
        raise HTTPException(status_code=422, detail="Name is required to create an account.")
    user_id = str(uuid.uuid4())
    try:
        with _connect() as connection:
            connection.execute(
                """
                INSERT INTO auth_users (id, email, name, password_hash, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    user_id,
                    credentials.email,
                    credentials.name,
                    _hash_password(credentials.password),
                    int(time.time()),
                ),
            )
            user = connection.execute(
                "SELECT * FROM auth_users WHERE id = ?", (user_id,)
            ).fetchone()
    except sqlite3.IntegrityError as error:
        raise HTTPException(
            status_code=409, detail="An account with this email already exists."
        ) from error
    return _auth_response(user)


@router.post("/login")
def login(credentials: Credentials) -> dict:
    _initialize()
    _session_secret()
    with _connect() as connection:
        user = connection.execute(
            "SELECT * FROM auth_users WHERE email = ?", (credentials.email,)
        ).fetchone()
    if user is None or not _verify_password(credentials.password, user["password_hash"]):
        raise HTTPException(status_code=401, detail="Email or password is incorrect.")
    return _auth_response(user)


@router.get("/me")
def me(user: sqlite3.Row = Depends(current_user)) -> dict:
    return _user_payload(user)


@router.post("/logout")
def logout(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    user: sqlite3.Row = Depends(current_user),
) -> dict[str, str]:
    payload = _decode_session(credentials.credentials)
    with _connect() as connection:
        connection.execute(
            "UPDATE auth_sessions SET revoked = 1 WHERE id = ? AND user_id = ?",
            (payload["jti"], user["id"]),
        )
    return {"status": "ok"}
