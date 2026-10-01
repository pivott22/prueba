import unittest
from server import normalize_item, pokemon_title

class ProductValidationTests(unittest.TestCase):
    def item(self, **changes):
        value = {"id": "MLC123456", "seller_id": 550072427, "currency_id": "CLP", "title": "Pokémon ETB español", "price": 49990, "permalink": "https://articulo.mercadolibre.cl/MLC-123456", "status": "active"}
        value.update(changes)
        return value

    def test_accents_and_title_boundaries(self):
        self.assertTrue(pokemon_title("Caja POKÉMON"))
        self.assertFalse(pokemon_title("Pokemonish"))

    def test_reject_wrong_seller_currency_and_price(self):
        for change in ({"seller_id": 2}, {"currency_id": "USD"}, {"price": -5}, {"price": True}, {"price": 12.5}):
            self.assertIsNone(normalize_item(self.item(**change)))

    def test_reject_misleading_urls(self):
        for url in ("https://mercadolibre.cl.evil.test/MLC-123456", "http://articulo.mercadolibre.cl/MLC-123456", "https://user@mercadolibre.cl/MLC-123456"):
            self.assertIsNone(normalize_item(self.item(permalink=url)))

    def test_price_does_not_imply_shipping_or_active(self):
        value = normalize_item(self.item(status="paused"))
        self.assertEqual(value["price"], 49990)
        self.assertFalse(value["active"])
        self.assertFalse(value["shipping_known"])

if __name__ == "__main__":
    unittest.main()
