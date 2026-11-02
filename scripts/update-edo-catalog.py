#!/usr/bin/env python3
"""Refresh the UI's emission-terms snapshot from official offer pages (Python 3.9+)."""

import argparse
import calendar
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from decimal import Decimal
from html.parser import HTMLParser
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/web/series.json"
BASE = "https://www.obligacjeskarbowe.pl/oferta-obligacji/obligacje-10-letnie-edo/"


class CatalogRefreshError(RuntimeError):
    pass


class PageText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)


def parse_terms(html, year, month):
    code = f"EDO{month:02}{(year + 10) % 100:02}"
    parser = PageText()
    parser.feed(html)
    text = re.sub(r"\s+", " ", " ".join(parser.parts))
    if not re.search(rf"Seria:\s*{code}\b", text):
        raise ValueError("response is not the requested series")
    if "Oprocentowanie:" not in text or "Kapitalizacja odsetek:" not in text:
        raise ValueError("interest-rate summary missing; page structure may have changed")
    summary = text.split("Oprocentowanie:", 1)[1].split("Kapitalizacja odsetek:", 1)[0]
    rate = re.search(r"([\d,\.]+)\s*%\s*w pierwszym rocznym okresie", summary, re.I)
    if rate is None:
        rate = re.search(r"W pierwszym rocznym okresie odsetkowym wynosi\s*([\d,\.]+)\s*%", text, re.I)
    margin = re.search(r"marża\s*([\d,\.]+)\s*%", summary, re.I)
    if rate is None or margin is None:
        raise ValueError("explicit first-period rate or margin missing; manual entry required")
    sale = re.search(r"Sprzedaż:\s*(\d{2}\.\d{2}\.\d{4})\s*-\s*(\d{2}\.\d{2}\.\d{4})", text)
    start = date(year, month, 1)
    end = date(year, month, calendar.monthrange(year, month)[1])
    if sale is None or tuple(datetime.strptime(day, "%d.%m.%Y").date() for day in sale.groups()) != (start, end):
        raise ValueError("sale dates do not match the requested month")
    values = [Decimal(match.group(1).replace(",", ".")) for match in (rate, margin)]
    if any(not value.is_finite() or not 0 <= value <= 1000 for value in values):
        raise ValueError("rate outside supported bounds")
    return dict(code=code, saleStart=start.isoformat(), saleEnd=end.isoformat(),
                firstPeriodRate=f"{values[0]:.2f}", margin=f"{values[1]:.2f}",
                sourceUrl=f"{BASE}{code.lower()}/")


def fetch_month(month):
    year, month_number = month
    code = f"EDO{month_number:02}{(year + 10) % 100:02}"
    request = Request(f"{BASE}{code.lower()}/", headers={"User-Agent": "EDO-Calculator-Catalog/1.0"})
    for attempt in range(3):
        try:
            with urlopen(request, timeout=30) as response:
                html = response.read().decode("utf-8")
            return parse_terms(html, year, month_number), None
        except (ValueError, IndexError) as error:
            return None, f"{code}: {error} ({request.full_url})"
        except OSError as error:
            retryable = not isinstance(error, HTTPError) or error.code == 429 or error.code >= 500
            if not retryable or attempt == 2:
                return None, f"{code}: {error} ({request.full_url})"
            time.sleep(attempt + 1)


def series_month(entry):
    return tuple(map(int, entry["saleStart"].split("-")[:2]))


def emission_months(previous, today, from_year, daily):
    current = (today.year, today.month)
    if daily:
        preceding = (today.year, today.month - 1) if today.month > 1 else (today.year - 1, 12)
        known = [series_month(entry) for entry in previous["series"] if series_month(entry) <= current]
        # Keep a previously verified historical page as a control for older HTML formats.
        months = {current, preceding, min(known) if known else preceding}
        if known:
            latest_year, latest_month = max(known)
            for index in range(latest_year * 12 + latest_month, today.year * 12 + today.month):
                year, month = divmod(index, 12)
                months.add((year, month + 1))
        return sorted(months)
    return [(year, month) for year in range(from_year, today.year + 1)
            for month in range(1, 13) if (year, month) <= current]


def terms(entry):
    return {key: value for key, value in entry.items() if key != "verifiedAt"}


def refresh_catalog(output, today, from_year=2017, daily=False, fetcher=fetch_month):
    previous = json.loads(output.read_text()) if output.exists() else {"series": []}
    months = emission_months(previous, today, from_year, daily)
    required = set(months) if daily else {
        (today.year, today.month), *(series_month(entry) for entry in previous["series"])
    }
    series = {entry["code"]: entry for entry in previous["series"]}
    refreshed = 0
    failures = []
    required_failures = []
    changed = False
    with ThreadPoolExecutor(max_workers=2) as pool:
        for month, (entry, error) in zip(months, pool.map(fetcher, months)):
            if entry is not None:
                old = series.get(entry["code"])
                if old is None or terms(old) != terms(entry):
                    series[entry["code"]] = dict(entry, verifiedAt=today.isoformat())
                    changed = True
                refreshed += 1
            else:
                failures.append(error)
                print(error, file=sys.stderr, flush=True)
                if month in required:
                    required_failures.append(error)
    if required_failures or refreshed == 0:
        raise CatalogRefreshError(
            f"Catalog refresh failed ({len(required_failures)} required series unavailable). "
            "Existing snapshot left unchanged. See the series errors above."
        )
    if changed:
        catalog = dict(updatedAt=today.isoformat(),
                       series=sorted(series.values(), key=lambda item: item["saleStart"], reverse=True))
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(".tmp")
        temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output)
    print(f"Verified {refreshed} series; {len(failures)} unavailable; "
          f"catalog {'updated' if changed else 'unchanged'} ({len(series)} total).")
    return changed


def main():
    today = datetime.now(ZoneInfo("Europe/Warsaw")).date()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-year", type=int, default=2017)
    parser.add_argument("--daily", action="store_true", help="Check current, previous and historical control series")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if not 2000 <= args.from_year <= today.year:
        parser.error("--from-year must be between 2000 and the current year")
    try:
        refresh_catalog(args.output, today, args.from_year, args.daily)
    except CatalogRefreshError as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
