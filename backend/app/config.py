"""Authentication and database configuration for the Oeil backend.

Model-provider configuration intentionally remains in ``backend/main.py`` so
the existing NVIDIA/OpenAI setup is not duplicated or overridden here.
"""

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
ENV_FILE = BACKEND_DIR / ".env"

# Load backend/.env when present. Existing process environment variables win.
try:
    from dotenv import load_dotenv

    load_dotenv(ENV_FILE, override=False)
except ImportError:  # pragma: no cover - dotenv is a declared dependency
    pass

DEFAULT_AUTH_DB_PATH = "oeil-auth.sqlite3"


def env_value(name: str, default: str = "") -> str:
    """Return a trimmed environment value, treating blank as unset."""
    return (os.getenv(name) or "").strip() or default


def database_path() -> str:
    # Preserve the original behavior: an empty value is used as-is.
    return os.getenv("OEIL_AUTH_DB_PATH", DEFAULT_AUTH_DB_PATH)


def auth_secret() -> str:
    return os.getenv("OEIL_AUTH_SECRET", "").strip()


def public_base_url() -> str:
    return os.getenv("OEIL_PUBLIC_BASE_URL", "").strip().rstrip("/")
