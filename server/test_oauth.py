import os
import tempfile
import unittest
import urllib.parse
from unittest.mock import patch
import oauth
import web


class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            "APP_API_KEY": "test-app-key-not-a-real-credential",
            "TOKEN_ENCRYPTION_KEY": "test-encryption-key-not-a-real-credential",
            "MELI_CLIENT_ID": "123456", "MELI_CLIENT_SECRET": "test-only",
            "PUBLIC_BASE_URL": "https://example.test", "TOKEN_FILE": self.folder.name + "/tokens.enc"})
        self.env.start()
        web.app.config.update(TESTING=True, SECRET_KEY="test-session-key")
        web.PENDING.clear()
        self.client = web.app.test_client()

    def tearDown(self):
        self.env.stop()
        self.folder.cleanup()

    def start(self):
        response = self.client.post("/oauth/start", data={"key": os.environ["APP_API_KEY"]}, base_url="https://example.test")
        self.assertEqual(response.status_code, 303)
        fields = urllib.parse.parse_qs(urllib.parse.urlparse(response.headers["Location"]).query)
        return fields

    def test_pkce_state_and_redirect(self):
        fields = self.start()
        self.assertEqual(fields["code_challenge_method"], ["S256"])
        self.assertEqual(fields["redirect_uri"], ["https://example.test/oauth/callback"])
        self.assertEqual(fields["scope"], ["read offline_access"])
        self.assertGreater(len(fields["state"][0]), 32)

    def test_private_routes_require_key(self):
        self.assertEqual(self.client.get("/products").status_code, 401)
        self.assertEqual(self.client.post("/oauth/start", data={"key": "wrong"}).status_code, 401)
        self.assertEqual(self.client.post("/diagnostic", data={"key": "wrong"}).status_code, 401)

    def test_callback_rejects_other_browser_and_replay(self):
        state = self.start()["state"][0]
        other = web.app.test_client()
        with patch.object(oauth, "token_request") as exchange:
            response = other.get("/oauth/callback", query_string={"state": state, "code": "fake"}, base_url="https://example.test")
            self.assertEqual(response.status_code, 400)
            exchange.assert_not_called()
        data = {"access_token": "fake-access", "refresh_token": "fake-refresh", "expires_in": 21600}
        with patch.object(oauth, "token_request", return_value=data) as exchange:
            response = self.client.get("/oauth/callback", query_string={"state": state, "code": "fake"}, base_url="https://example.test")
            self.assertEqual(response.status_code, 200)
            again = self.client.get("/oauth/callback", query_string={"state": state, "code": "fake"}, base_url="https://example.test")
            self.assertEqual(again.status_code, 400)
            self.assertEqual(exchange.call_count, 1)

    def test_tokens_encrypted_and_refresh_rotated(self):
        with oauth.TOKEN_LOCK:
            oauth.save_tokens({"access_token": "old-token", "refresh_token": "old-refresh", "expires_in": 1})
        self.assertNotIn(b"old-token", oauth.token_file().read_bytes())
        new = {"access_token": "new-token", "refresh_token": "new-refresh", "expires_in": 21600}
        with patch.object(oauth, "token_request", return_value=new) as exchange:
            self.assertEqual(oauth.access_token(), "new-token")
            self.assertEqual(exchange.call_args[0][0]["refresh_token"], "old-refresh")
            self.assertEqual(oauth.read_tokens()["refresh_token"], "new-refresh")

    def test_invalid_origin_never_accepted(self):
        with patch.dict(os.environ, {"PUBLIC_BASE_URL": "http://example.test"}):
            with self.assertRaises(RuntimeError):
                oauth.begin_authorization()


if __name__ == "__main__":
    unittest.main()
