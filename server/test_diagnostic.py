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

    def test_direct_item_can_succeed_while_seller_search_rejected(self):
        item = {"id": "MLC4261910624", "seller_id": 550072427, "currency_id": "CLP", "title": "Pokemon TCG Blister", "price": 20000, "permalink": "https://articulo.mercadolibre.cl/MLC-4261910624", "status": "active", "private_field": "do-not-disclose"}
        with patch.object(server, "api_get", side_effect=[{"id": 123}, {}, server.MercadoLibreError(403, "seller"), item]) as api:
            result = server.diagnose_connection("MLC4261910624")
        self.assertEqual(result["seller_listings_http"], 403)
        self.assertEqual(result["item_http"], 200)
        self.assertTrue(result["item_seller_matches"])
        self.assertNotIn("private_field", str(result))
        self.assertTrue(api.call_args[0][0].startswith("/items/MLC4261910624?"))

    def test_direct_item_rejection_is_reported_without_claiming_access(self):
        with patch.object(server, "api_get", side_effect=[{"id": 123}, {}, server.MercadoLibreError(403, "seller"), server.MercadoLibreError(403, "item")]):
            result = server.diagnose_connection("MLC4261910624")
        self.assertEqual(result["item_finding"], "direct_item_access_rejected")
        self.assertEqual(result["item_http"], 403)

    def test_reject_invalid_item_before_network_call(self):
        with patch.object(server, "api_get") as api:
            with self.assertRaises(ValueError):
                server.diagnose_connection("MLC123/../../users/me")
            api.assert_not_called()

if __name__ == "__main__":
    unittest.main()
