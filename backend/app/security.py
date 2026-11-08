"""Security primitives for password hashing and session tokens.

Generate ``OEIL_AUTH_SECRET`` with a cryptographically secure random source, for
example::

    openssl rand -hex 32

or::

    python -c "import secrets; print(secrets.token_hex(32))"
"""

import base64
import hashlib
import hmac
import json
import secrets
import time

from fastapi import HTTPException

from app import config as app_config

SESSION_TTL_SECONDS = 60 * 60 * 24 * 30
PASSWORD_ROUNDS = 310_000

_WEAK_SECRET_SEQUENCES = (
    "0123456789",
    "abcdefghijklmnopqrstuvwxyz",
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
    "password",
    "changeme",
    "letmein",
    "default",
    "admin",
)


def _is_weak_secret(secret: str) -> bool:
    """Reject low-diversity, periodic, and obvious placeholder secrets."""
    if len(secret) < 32:
        return True
    if len(set(secret)) < 8:
        return True
    lowered = secret.casefold()
    if any(sequence in lowered for sequence in _WEAK_SECRET_SEQUENCES):
        return True
    for period in range(1, min(64, len(secret) // 2) + 1):
        if secret == (secret[:period] * (len(secret) // period + 1))[: len(secret)]:
            return True
    return False


def session_secret() -> bytes:
    secret = app_config.auth_secret()
    if _is_weak_secret(secret):
        raise HTTPException(
            status_code=503,
            detail=(
                "Authentication is not configured. Set OEIL_AUTH_SECRET to a "
                "random value of at least 32 characters. Generate one with "
                "`openssl rand -hex 32` or "
                "`python -c \"import secrets; print(secrets.token_hex(32))\"`."
            ),
        )
    return secret.encode("utf-8")


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_ROUNDS
    )
    return "pbkdf2_sha256${}${}${}".format(
        PASSWORD_ROUNDS,
        base64.urlsafe_b64encode(salt).decode("ascii"),
        base64.urlsafe_b64encode(digest).decode("ascii"),
    )


def verify_password(password: str, stored_hash: str | None) -> bool:
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


def create_session_token(user_id: str) -> tuple[str, str, int]:
    secret = session_secret()
    now = int(time.time())
    session_id = secrets.token_urlsafe(24)
    payload = {"sub": user_id, "jti": session_id, "exp": now + SESSION_TTL_SECONDS}
    unsigned = f"{_encode_part({'alg': 'HS256', 'typ': 'JWT'})}.{_encode_part(payload)}"
    signature = hmac.new(secret, unsigned.encode("ascii"), hashlib.sha256).digest()
    token = f"{unsigned}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode('ascii')}"
    return token, session_id, now + SESSION_TTL_SECONDS


def decode_session(token: str) -> dict:
    secret = session_secret()
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
