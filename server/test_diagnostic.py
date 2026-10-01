import unittest
from unittest.mock import patch
import server


class DiagnosticTests(unittest.TestCase):
    def test_target_restricted_with_valid_own_access(self):
        replies = [{"id": 123, "email": "private@example.test"}, {"results": []}, server.MercadoLibreError(403, "seller")]
        with patch.object(server, "api_get", side_effect=replies):
            result = server.diagnose_connection()
        self.assertEqual(result["finding"], "target_seller_access_rejected")
        self.assertNotIn("email", str(result))
        self.assertNotIn("private", str(result))

    def test_own_resource_also_restricted(self):
        with patch.object(server, "api_get", side_effect=[{"id": 123}, server.MercadoLibreError(403, "own"), server.MercadoLibreError(403, "seller")]):
            self.assertEqual(server.diagnose_connection()["finding"], "listing_resource_access_rejected")

    def test_invalid_account_stops_further_calls(self):
        with patch.object(server, "api_get", side_effect=server.MercadoLibreError(401, "account")) as api:
            result = server.diagnose_connection()
        self.assertEqual(result["account_http"], 401)
        self.assertEqual(api.call_count, 1)

    def test_search_success_does_not_claim_prices_confirmed(self):
        with patch.object(server, "api_get", side_effect=[{"id": 123}, {"results": []}, {"results": []}]):
            self.assertEqual(server.diagnose_connection()["finding"], "seller_search_access_confirmed")

if __name__ == "__main__":
    unittest.main()
