import importlib.util
import json
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

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

    def test_missing_summary_is_reported_as_parse_error(self):
        with self.assertRaisesRegex(ValueError, "structure may have changed"):
            catalog.parse_terms(self.page().replace("Oprocentowanie:", "Stopa procentowa:"), 2020, 1)

    def test_network_failures_have_bounded_retries(self):
        with patch.object(catalog, "urlopen", side_effect=OSError("timeout")) as get, \
                patch.object(catalog.time, "sleep") as sleep:
            entry, error = catalog.fetch_month((2020, 1))
        self.assertIsNone(entry)
        self.assertIn("EDO0130: timeout", error)
        self.assertEqual(get.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2])

    def test_missing_page_is_not_retried(self):
        missing = HTTPError("https://example.test/", 404, "Not Found", {}, None)
        with patch.object(catalog, "urlopen", side_effect=missing) as get:
            entry, error = catalog.fetch_month((2020, 1))
        self.assertIsNone(entry)
        self.assertIn("404", error)
        self.assertEqual(get.call_count, 1)


def entry(year, month, rate="5.75", verified="2026-10-01"):
    code = f"EDO{month:02}{(year + 10) % 100:02}"
    return dict(code=code, saleStart=f"{year}-{month:02}-01",
                saleEnd=f"{year}-{month:02}-{catalog.calendar.monthrange(year, month)[1]}",
                firstPeriodRate=rate, margin="2.00", sourceUrl=f"{catalog.BASE}{code.lower()}/",
                verifiedAt=verified)


class RefreshTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name) / "series.json"
        self.initial = {"updatedAt": "2026-10-01", "series": [entry(2026, 10), entry(2020, 1)]}
        self.output.write_text(json.dumps(self.initial))
        self.original = self.output.read_bytes()

    def fetch(self, month):
        return catalog.terms(entry(*month)), None

    def test_daily_months_include_control_year_boundary_and_missed_emissions(self):
        self.assertEqual(catalog.emission_months(self.initial, date(2027, 1, 2), 2017, True),
                         [(2020, 1), (2026, 11), (2026, 12), (2027, 1)])

    def test_no_terms_change_leaves_snapshot_byte_identical_even_on_next_day(self):
        for today in [date(2026, 11, 2), date(2026, 11, 3)]:
            # First run adds November; later checks must not rewrite dates or whitespace.
            changed = catalog.refresh_catalog(self.output, today, daily=True, fetcher=self.fetch)
            if today.day == 2:
                self.assertTrue(changed)
                saved = self.output.read_bytes()
            else:
                self.assertFalse(changed)
                self.assertEqual(self.output.read_bytes(), saved)

    def test_new_series_keeps_history_and_dates_of_unchanged_entries(self):
        catalog.refresh_catalog(self.output, date(2026, 11, 2), daily=True, fetcher=self.fetch)
        result = json.loads(self.output.read_text())
        self.assertEqual([e["code"] for e in result["series"]], ["EDO1136", "EDO1036", "EDO0130"])
        self.assertEqual(result["series"][0]["verifiedAt"], "2026-11-02")
        self.assertEqual(result["series"][1:], self.initial["series"])

    def test_changed_rate_is_persisted(self):
        def changed(month):
            return catalog.terms(entry(*month, rate="5.35" if month == (2026, 10) else "5.75")), None
        catalog.refresh_catalog(self.output, date(2026, 11, 2), daily=True, fetcher=changed)
        result = json.loads(self.output.read_text())
        self.assertEqual(result["series"][1]["firstPeriodRate"], "5.35")
        self.assertEqual(result["series"][1]["verifiedAt"], "2026-11-02")

    def test_failure_of_any_daily_required_page_keeps_entire_original_snapshot(self):
        for failed in [(2026, 11), (2026, 10), (2020, 1)]:
            with self.subTest(failed=failed):
                def fetch(month):
                    return (None, f"{month}: changed HTML") if month == failed else self.fetch(month)
                with self.assertRaises(catalog.CatalogRefreshError):
                    catalog.refresh_catalog(self.output, date(2026, 11, 2), daily=True, fetcher=fetch)
                self.assertEqual(self.output.read_bytes(), self.original)
                self.assertFalse(self.output.with_suffix(".tmp").exists())

    def test_full_refresh_also_fails_when_current_series_is_missing(self):
        def fetch(month):
            return (None, "current series missing") if month == (2026, 11) else self.fetch(month)
        with self.assertRaises(catalog.CatalogRefreshError):
            catalog.refresh_catalog(self.output, date(2026, 11, 2), from_year=2026, fetcher=fetch)
        self.assertEqual(self.output.read_bytes(), self.original)

    def test_full_refresh_does_not_hide_failure_of_a_previously_known_series(self):
        def fetch(month):
            return (None, "known series missing") if month == (2026, 10) else self.fetch(month)
        with self.assertRaises(catalog.CatalogRefreshError):
            catalog.refresh_catalog(self.output, date(2026, 11, 2), from_year=2026, fetcher=fetch)
        self.assertEqual(self.output.read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
