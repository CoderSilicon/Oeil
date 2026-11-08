import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

import auth
from app import rate_limit, security
from app.db import connect, initialize
from app.db import oauth
from app.services import auth_service
from main import app

STRONG_SECRET = "test-auth-secret-that-is-at-least-32-characters"


class AuthSecurityTests(unittest.TestCase):
    def setUp(self):
        rate_limit.reset()
        self.addCleanup(rate_limit.reset)

        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)

        self.env_patcher = patch.dict(
            os.environ,
            {
                "OEIL_AUTH_DB_PATH": os.path.join(
                    self.tempdir.name, "auth.sqlite3"
                ),
                "OEIL_AUTH_SECRET": STRONG_SECRET,
            },
        )
        self.env_patcher.start()
        self.addCleanup(self.env_patcher.stop)
        initialize()

    def _create_local_user(self, email="victim@example.com"):
        return auth_service.signup(
            email=email,
            password="safe-password",
            name="Local User",
        )

    def _oauth_link_exists(self, provider, provider_user_id):
        with connect() as connection:
            return oauth.find_account(connection, provider, provider_user_id) is not None

    def test_oauth_does_not_auto_link_an_existing_email(self):
        self._create_local_user()

        with self.assertRaises(HTTPException) as raised:
            auth._get_or_create_oauth_user(
                "google",
                "google-existing-email",
                "victim@example.com",
                "Victim",
                True,
            )

        self.assertEqual(raised.exception.status_code, 409)
        self.assertFalse(
            self._oauth_link_exists("google", "google-existing-email")
        )

    def test_verified_oauth_creates_a_new_account(self):
        user = auth._get_or_create_oauth_user(
            "google",
            "google-new-user",
            "new@example.com",
            "New User",
            True,
        )

        self.assertEqual(user["email"], "new@example.com")
        self.assertTrue(self._oauth_link_exists("google", "google-new-user"))

    def test_existing_oauth_link_still_signs_in_without_reverification(self):
        first = auth._get_or_create_oauth_user(
            "microsoft",
            "microsoft-linked",
            "linked@example.com",
            "Linked User",
            True,
        )
        second = auth._get_or_create_oauth_user(
            "microsoft",
            "microsoft-linked",
            "linked@example.com",
            "Linked User",
            False,
        )

        self.assertEqual(first["id"], second["id"])

    def test_unverified_oauth_email_is_rejected(self):
        with self.assertRaises(HTTPException) as raised:
            auth._get_or_create_oauth_user(
                "microsoft",
                "microsoft-unverified",
                "unverified@example.com",
                "Unverified User",
                False,
            )

        self.assertEqual(raised.exception.status_code, 403)
        self.assertFalse(
            self._oauth_link_exists("microsoft", "microsoft-unverified")
        )

    def test_microsoft_requires_an_authoritative_verification_claim(self):
        self.assertFalse(
            auth._provider_email_verified(
                "microsoft", {"mail": "user@example.com"}
            )
        )
        self.assertFalse(
            auth._provider_email_verified(
                "microsoft", {"userPrincipalName": "user@example.com"}
            )
        )
        self.assertTrue(
            auth._provider_email_verified(
                "microsoft", {"email_verified": True}
            )
        )
        self.assertTrue(
            auth._provider_email_verified(
                "microsoft", {"verified_primary_email": True}
            )
        )

    def test_concurrent_oauth_creation_is_idempotent(self):
        def create_account(_):
            return auth._get_or_create_oauth_user(
                "google",
                "google-race",
                "race@example.com",
                "Race User",
                True,
            )["id"]

        with ThreadPoolExecutor(max_workers=8) as pool:
            user_ids = list(pool.map(create_account, range(8)))

        self.assertEqual(len(set(user_ids)), 1)
        with connect() as connection:
            user_count = connection.execute(
                "SELECT COUNT(*) FROM auth_users WHERE email = ?",
                ("race@example.com",),
            ).fetchone()[0]
            link_count = connection.execute(
                "SELECT COUNT(*) FROM oauth_accounts "
                "WHERE provider = ? AND provider_user_id = ?",
                ("google", "google-race"),
            ).fetchone()[0]
        self.assertEqual(user_count, 1)
        self.assertEqual(link_count, 1)

    def test_weak_secrets_are_rejected(self):
        weak_secrets = (
            "a" * 32,
            "0123456789abcdef" * 2,
            "abcdefgh" * 4,
            "A1b2C3d4E5f6G7h8I" * 2,
        )
        for weak_secret in weak_secrets:
            with self.subTest(secret=weak_secret), patch.dict(
                os.environ, {"OEIL_AUTH_SECRET": weak_secret}
            ):
                with self.assertRaises(HTTPException) as raised:
                    security.session_secret()
                self.assertEqual(raised.exception.status_code, 503)

    def test_strong_secret_used_by_existing_tests_is_accepted(self):
        self.assertEqual(security.session_secret(), STRONG_SECRET.encode("utf-8"))

    def test_login_rate_limit(self):
        client = TestClient(app)
        body = {"email": "nobody@example.com", "password": "wrong-password"}

        for _ in range(rate_limit.LOGIN_LIMIT.max_requests):
            response = client.post("/auth/login", json=body)
            self.assertEqual(response.status_code, 401)

        limited = client.post("/auth/login", json=body)
        self.assertEqual(limited.status_code, 429)
        self.assertIn("Retry-After", limited.headers)

    def test_signup_rate_limit(self):
        client = TestClient(app)

        for index in range(rate_limit.SIGNUP_LIMIT.max_requests):
            response = client.post(
                "/auth/signup",
                json={
                    "name": "Rate Limited",
                    "email": f"signup-{index}@example.com",
                    "password": "safe-password",
                },
            )
            self.assertEqual(response.status_code, 200)

        limited = client.post(
            "/auth/signup",
            json={
                "name": "Rate Limited",
                "email": "signup-limited@example.com",
                "password": "safe-password",
            },
        )
        self.assertEqual(limited.status_code, 429)
        self.assertIn("Retry-After", limited.headers)

    def test_oauth_start_rate_limit(self):
        with patch.dict(
            os.environ,
            {
                "OEIL_PUBLIC_BASE_URL": "https://api.example.test",
                "GOOGLE_CLIENT_ID": "test-client",
                "GOOGLE_CLIENT_SECRET": "test-secret",
            },
        ):
            client = TestClient(app)
            for _ in range(rate_limit.OAUTH_START_LIMIT.max_requests):
                response = client.get(
                    "/auth/oauth/google/start", follow_redirects=False
                )
                self.assertEqual(response.status_code, 302)

            limited = client.get(
                "/auth/oauth/google/start", follow_redirects=False
            )
        self.assertEqual(limited.status_code, 429)
        self.assertIn("Retry-After", limited.headers)

    def test_oauth_callback_rate_limit(self):
        client = TestClient(app)
        for _ in range(rate_limit.OAUTH_CALLBACK_LIMIT.max_requests):
            response = client.get(
                "/auth/oauth/google/callback", follow_redirects=False
            )
            self.assertEqual(response.status_code, 302)

        limited = client.get(
            "/auth/oauth/google/callback", follow_redirects=False
        )
        self.assertEqual(limited.status_code, 429)
        self.assertIn("Retry-After", limited.headers)


if __name__ == "__main__":
    unittest.main()
