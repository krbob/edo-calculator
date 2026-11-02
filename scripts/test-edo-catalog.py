import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("catalog", Path(__file__).with_name("update-edo-catalog.py"))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class CatalogTest(unittest.TestCase):
    def page(self, rate="2,70% w pierwszym rocznym okresie odsetkowym", sale="01.01.2020 - 31.01.2020"):
        return f"""<p>Seria: EDO0130</p><p>Oprocentowanie: {rate},
        w kolejnych rocznych okresach odsetkowych: marża 1,50% + inflacja</p>
        <p>Kapitalizacja odsetek: roczna</p><p>Sprzedaż: {sale}</p>"""

    def test_explicit_summary(self):
        result = catalog.parse_terms(self.page(), 2020, 1)
        self.assertEqual(result["firstPeriodRate"], "2.70")
        self.assertEqual(result["margin"], "1.50")

    def test_legacy_first_period_description(self):
        page = self.page(rate="w pierwszym rocznym okresie odsetkowym")
        page += "<p>W pierwszym rocznym okresie odsetkowym wynosi 2,70%.</p>"
        self.assertEqual(catalog.parse_terms(page, 2020, 1)["firstPeriodRate"], "2.70")

    def test_does_not_guess_from_examples_or_redirects(self):
        page = self.page(rate="w pierwszym rocznym okresie odsetkowym")
        page += "<p>Jan kupił EDO0524: oprocentowanie wynosi 4,00% w pierwszym okresie.</p>"
        for html in [page, self.page().replace("EDO0130", "EDO0230"), self.page(sale="01.01.2020 - 30.01.2020")]:
            with self.assertRaises(ValueError):
                catalog.parse_terms(html, 2020, 1)


if __name__ == "__main__":
    unittest.main()
