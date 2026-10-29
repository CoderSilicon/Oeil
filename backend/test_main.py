import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import ChatRequest, app, provider_payload


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


if __name__ == "__main__":
    unittest.main()
