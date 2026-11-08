"""Shared FastAPI authentication dependencies."""

import sqlite3
import time

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app import security
from app.db import connect, initialize, sessions, users

__all__ = ["bearer", "current_user"]

bearer = HTTPBearer(auto_error=False)


async def current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> sqlite3.Row:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="Sign in to continue.")
    payload = security.decode_session(credentials.credentials)
    initialize()
    with connect() as connection:
        session = sessions.find_by_id(
            connection, payload.get("jti"), payload.get("sub")
        )
        if (
            session is None
            or session["revoked"]
            or session["expires_at"] <= int(time.time())
        ):
            raise HTTPException(status_code=401, detail="Session has expired.")
        user = users.find_by_id(connection, payload.get("sub"))
    if user is None:
        raise HTTPException(status_code=401, detail="Account no longer exists.")
    return user
