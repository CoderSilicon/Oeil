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

    def test_social_auth_routes_are_not_available(self):
        response = TestClient(app).get("/auth/oauth/google/start")
        self.assertEqual(response.status_code, 404)

    def test_password_hashes_are_salted_and_verified(self):
        first = _hash_password("safe-password")
        second = _hash_password("safe-password")
        self.assertNotEqual(first, second)
        self.assertTrue(_verify_password("safe-password", first))
        self.assertFalse(_verify_password("wrong-password", first))

if __name__ == "__main__":
    unittest.main()
