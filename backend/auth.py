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
from urllib.parse import urlencode, urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field, field_validator
from starlette.responses import RedirectResponse

router = APIRouter(prefix="/auth", tags=["authentication"])
bearer = HTTPBearer(auto_error=False)
SESSION_TTL_SECONDS = 60 * 60 * 24 * 30
PASSWORD_ROUNDS = 310_000
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
APP_REDIRECT_URI = "oeil://auth/callback"

PROVIDERS = {
    "google": {
        "client_id": "GOOGLE_CLIENT_ID",
        "client_secret": "GOOGLE_CLIENT_SECRET",
        "authorize": "https://accounts.google.com/o/oauth2/v2/auth",
        "token": "https://oauth2.googleapis.com/token",
        "profile": "https://openidconnect.googleapis.com/v1/userinfo",
        "scope": "openid email profile",
    },
    "microsoft": {
        "client_id": "MICROSOFT_CLIENT_ID",
        "client_secret": "MICROSOFT_CLIENT_SECRET",
        "authorize": "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "token": "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "profile": "https://graph.microsoft.com/v1.0/me",
        "scope": "openid email profile User.Read",
    },
    "github": {
        "client_id": "GITHUB_CLIENT_ID",
        "client_secret": "GITHUB_CLIENT_SECRET",
        "authorize": "https://github.com/login/oauth/authorize",
        "token": "https://github.com/login/oauth/access_token",
        "profile": "https://api.github.com/user",
        "scope": "read:user user:email",
    },
}


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


def _get_or_create_oauth_user(
    provider: str, provider_user_id: str, email: str, name: str
) -> sqlite3.Row:
    normalized_email = email.strip().casefold()
    if not EMAIL_PATTERN.fullmatch(normalized_email):
        raise HTTPException(status_code=400, detail="Provider did not return a valid email.")
    display_name = name.strip()[:80] or normalized_email.split("@", 1)[0]
    with _connect() as connection:
        account = connection.execute(
            """
            SELECT auth_users.* FROM oauth_accounts
            JOIN auth_users ON auth_users.id = oauth_accounts.user_id
            WHERE provider = ? AND provider_user_id = ?
            """,
            (provider, provider_user_id),
        ).fetchone()
        if account:
            return account
        user = connection.execute(
            "SELECT * FROM auth_users WHERE email = ?", (normalized_email,)
        ).fetchone()
        if user is None:
            user_id = str(uuid.uuid4())
            connection.execute(
                "INSERT INTO auth_users (id, email, name, created_at) VALUES (?, ?, ?, ?)",
                (user_id, normalized_email, display_name, int(time.time())),
            )
        else:
            user_id = user["id"]
        connection.execute(
            "INSERT INTO oauth_accounts (provider, provider_user_id, user_id) VALUES (?, ?, ?)",
            (provider, provider_user_id, user_id),
        )
        return connection.execute(
            "SELECT * FROM auth_users WHERE id = ?", (user_id,)
        ).fetchone()


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


def _provider_config(provider: str) -> dict:
    config = PROVIDERS.get(provider)
    if config is None:
        raise HTTPException(status_code=404, detail="Unsupported sign-in provider.")
    client_id = os.getenv(config["client_id"], "").strip()
    client_secret = os.getenv(config["client_secret"], "").strip()
    public_base = os.getenv("OEIL_PUBLIC_BASE_URL", "").strip().rstrip("/")
    if not client_id or not client_secret or not public_base:
        raise HTTPException(
            status_code=503,
            detail=f"{provider.title()} sign-in is not configured on this backend.",
        )
    parsed_base = urlparse(public_base)
    if parsed_base.scheme != "https" or not parsed_base.netloc:
        raise HTTPException(
            status_code=503,
            detail="OEIL_PUBLIC_BASE_URL must be the public HTTPS backend URL.",
        )
    return {**config, "id": client_id, "secret": client_secret, "base": public_base}


@router.get("/oauth/{provider}/start")
def oauth_start(provider: str, redirect_uri: str = APP_REDIRECT_URI) -> RedirectResponse:
    if redirect_uri != APP_REDIRECT_URI:
        raise HTTPException(status_code=400, detail="Invalid application redirect URI.")
    config = _provider_config(provider)
    state = secrets.token_urlsafe(32)
    expires_at = int(time.time()) + 600
    _initialize()
    with _connect() as connection:
        connection.execute(
            "DELETE FROM oauth_states WHERE expires_at <= ?", (int(time.time()),)
        )
        connection.execute(
            "INSERT INTO oauth_states (state, provider, redirect_uri, expires_at) VALUES (?, ?, ?, ?)",
            (state, provider, redirect_uri, expires_at),
        )
    callback = f"{config['base']}/auth/oauth/{provider}/callback"
    query = {
        "client_id": config["id"],
        "redirect_uri": callback,
        "response_type": "code",
        "scope": config["scope"],
        "state": state,
    }
    if provider == "google":
        query["access_type"] = "online"
    if provider == "github":
        query["allow_signup"] = "true"
    return RedirectResponse(
        f"{config['authorize']}?{urlencode(query)}", status_code=302
    )


async def _provider_profile(
    provider: str, config: dict, code: str, callback: str
) -> tuple[str, str, str]:
    async with httpx.AsyncClient(timeout=15.0) as client:
        token_response = await client.post(
            config["token"],
            data={
                "client_id": config["id"],
                "client_secret": config["secret"],
                "code": code,
                "redirect_uri": callback,
                "grant_type": "authorization_code",
            },
            headers={"Accept": "application/json"},
        )
        if not token_response.is_success:
            raise HTTPException(status_code=502, detail="Could not exchange provider authorization.")
        access_token = token_response.json().get("access_token")
        if not isinstance(access_token, str) or not access_token:
            raise HTTPException(status_code=502, detail="Provider did not return an access token.")
        headers = {"Authorization": f"Bearer {access_token}", "Accept": "application/json"}
        if provider == "github":
            headers["User-Agent"] = "Oeil"
        profile_response = await client.get(config["profile"], headers=headers)
        if not profile_response.is_success:
            raise HTTPException(status_code=502, detail="Could not load provider account.")
        profile = profile_response.json()
        email = profile.get("email") or profile.get("mail") or profile.get("userPrincipalName")
        if provider == "github":
            email = None
            emails_response = await client.get(
                "https://api.github.com/user/emails", headers=headers
            )
            if not emails_response.is_success:
                raise HTTPException(status_code=502, detail="Could not verify the GitHub account email.")
            emails = emails_response.json()
            email = next(
                (
                    item["email"]
                    for item in emails
                    if item.get("verified") and item.get("primary")
                ),
                None,
            )
        if provider == "google" and profile.get("email_verified") is not True:
            raise HTTPException(status_code=403, detail="Verify your Google email before signing in.")
        if provider == "github" and not email:
            raise HTTPException(status_code=403, detail="A verified GitHub email is required.")
        provider_user_id = str(profile.get("sub") or profile.get("id") or "")
        name = profile.get("name") or profile.get("displayName") or profile.get("login") or email
        if not provider_user_id or not isinstance(email, str):
            raise HTTPException(status_code=502, detail="Provider returned an incomplete account profile.")
        return provider_user_id, email, str(name)


@router.get("/oauth/{provider}/callback")
async def oauth_callback(
    provider: str,
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
) -> RedirectResponse:
    if provider not in PROVIDERS:
        raise HTTPException(status_code=404, detail="Unsupported sign-in provider.")
    app_redirect = APP_REDIRECT_URI
    if error or not code or not state:
        return RedirectResponse(
            f"{app_redirect}?{urlencode({'error': 'Sign-in was cancelled or not authorized.'})}",
            status_code=302,
        )
    _session_secret()
    _initialize()
    with _connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        saved_state = connection.execute(
            "SELECT * FROM oauth_states WHERE state = ? AND provider = ?",
            (state, provider),
        ).fetchone()
        if saved_state:
            connection.execute("DELETE FROM oauth_states WHERE state = ?", (state,))
    if (
        saved_state is None
        or saved_state["expires_at"] <= int(time.time())
        or saved_state["redirect_uri"] != app_redirect
    ):
        return RedirectResponse(
            f"{app_redirect}?{urlencode({'error': 'Sign-in expired. Please try again.'})}",
            status_code=302,
        )
    config = _provider_config(provider)
    callback = f"{config['base']}/auth/oauth/{provider}/callback"
    try:
        provider_id, email, name = await _provider_profile(provider, config, code, callback)
        user = _get_or_create_oauth_user(provider, provider_id, email, name)
        token = _issue_session(user["id"])
    except httpx.RequestError as request_error:
        raise HTTPException(status_code=502, detail="Could not connect to the sign-in provider.") from request_error
    return RedirectResponse(f"{app_redirect}#token={token}", status_code=302)
