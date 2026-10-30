import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import ChatRequest, app, provider_payload
from auth import _hash_password, _verify_password


class BackendTests(unittest.TestCase):
    def test_health_is_available_without_provider_credentials(self):
        response = TestClient(app).get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_chat_reports_missing_key_instead_of_faking_a_reply(self):
        with patch.dict(os.environ, {"NEBIUS_API_KEY": ""}):
            response = TestClient(app).post(
                "/chat",
                json={"mode": "AUTO", "messages": [{"role": "user", "content": "Hi"}]},
            )
        self.assertEqual(response.status_code, 503)
        self.assertIn("NEBIUS_API_KEY", response.json()["detail"])

    def test_mode_selects_provider_instruction_and_model(self):
        request = ChatRequest(
            mode="PRECISE",
            messages=[{"role": "user", "content": "Explain this briefly."}],
        )
        with patch.dict(os.environ, {"NEBIUS_MODEL": "test/model"}):
            payload = provider_payload(request)

        self.assertEqual(payload["model"], "test/model")
        self.assertEqual(payload["temperature"], 0.2)
        self.assertEqual(payload["messages"][0]["role"], "system")
        self.assertEqual(payload["messages"][1]["role"], "user")

    def test_signup_login_me_and_logout(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {
                "OEIL_AUTH_DB_PATH": f"{directory}/auth.sqlite3",
                "OEIL_AUTH_SECRET": "test-auth-secret-that-is-at-least-32-characters",
            },
        ):
            client = TestClient(app)
            signup = client.post(
                "/auth/signup",
                json={"name": "Ada Lovelace", "email": " ADA@example.com ", "password": "safe-password"},
            )
            self.assertEqual(signup.status_code, 200)
            account = signup.json()
            self.assertEqual(account["user"]["email"], "ada@example.com")
            self.assertNotIn("password", account)

            login = client.post(
                "/auth/login",
                json={"email": "ada@example.com", "password": "safe-password"},
            )
            self.assertEqual(login.status_code, 200)
            headers = {"Authorization": f"Bearer {login.json()['token']}"}
            self.assertEqual(client.get("/auth/me", headers=headers).json()["name"], "Ada Lovelace")
            self.assertEqual(client.post("/auth/logout", headers=headers).status_code, 200)
            self.assertEqual(client.get("/auth/me", headers=headers).status_code, 401)

    def test_signup_rejects_duplicate_email_and_bad_login(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {
                "OEIL_AUTH_DB_PATH": f"{directory}/auth.sqlite3",
                "OEIL_AUTH_SECRET": "test-auth-secret-that-is-at-least-32-characters",
            },
        ):
            client = TestClient(app)
            body = {"name": "Ada", "email": "ada@example.com", "password": "safe-password"}
            self.assertEqual(client.post("/auth/signup", json=body).status_code, 200)
            self.assertEqual(client.post("/auth/signup", json=body).status_code, 409)
            bad_login = client.post(
                "/auth/login",
                json={"email": "ada@example.com", "password": "wrong-password"},
            )
            self.assertEqual(bad_login.status_code, 401)

    def test_auth_is_disabled_without_a_session_secret(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"OEIL_AUTH_DB_PATH": f"{directory}/auth.sqlite3"},
            clear=True,
        ):
            response = TestClient(app).post(
                "/auth/signup",
                json={"name": "Ada", "email": "ada@example.com", "password": "safe-password"},
            )
        self.assertEqual(response.status_code, 503)

    def test_password_hashes_are_salted_and_verified(self):
        first = _hash_password("safe-password")
        second = _hash_password("safe-password")
        self.assertNotEqual(first, second)
        self.assertTrue(_verify_password("safe-password", first))
        self.assertFalse(_verify_password("wrong-password", first))

    def test_oauth_start_requires_provider_configuration(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"OEIL_AUTH_DB_PATH": f"{directory}/auth.sqlite3"},
            clear=True,
        ):
            response = TestClient(app).get("/auth/oauth/google/start")
        self.assertEqual(response.status_code, 503)

    def test_oauth_start_uses_a_fixed_app_redirect(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {
                "OEIL_AUTH_DB_PATH": f"{directory}/auth.sqlite3",
                "OEIL_PUBLIC_BASE_URL": "https://api.example.test",
                "GOOGLE_CLIENT_ID": "test-client",
                "GOOGLE_CLIENT_SECRET": "test-secret",
            },
        ):
            client = TestClient(app)
            invalid = client.get(
                "/auth/oauth/google/start",
                params={"redirect_uri": "https://attacker.example"},
            )
            response = client.get("/auth/oauth/google/start", follow_redirects=False)
        self.assertEqual(invalid.status_code, 400)
        self.assertEqual(response.status_code, 302)
        self.assertIn("accounts.google.com", response.headers["location"])
        self.assertIn("https%3A%2F%2Fapi.example.test%2Fauth%2Foauth%2Fgoogle%2Fcallback", response.headers["location"])


if __name__ == "__main__":
    unittest.main()
