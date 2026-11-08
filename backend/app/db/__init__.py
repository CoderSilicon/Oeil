"""Database connection and schema initialization."""

from app.db.connection import connect, database_path
from app.db.schema import initialize

__all__ = ["connect", "database_path", "initialize"]
