#!/usr/bin/env python3
"""Refresh the UI's emission-terms snapshot from official offer pages (Python 3.9+)."""

import argparse
import calendar
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from decimal import Decimal
from html.parser import HTMLParser
from pathlib import Path
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/web/series.json"
BASE = "https://www.obligacjeskarbowe.pl/oferta-obligacji/obligacje-10-letnie-edo/"


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
    try:
        request = Request(f"{BASE}{code.lower()}/", headers={"User-Agent": "EDO-Calculator-Catalog/1.0"})
        with urlopen(request, timeout=30) as response:
            return parse_terms(response.read().decode("utf-8"), year, month_number), None
    except (OSError, ValueError, IndexError) as error:
        return None, f"{code}: {error}"


def main():
    today = datetime.now(ZoneInfo("Europe/Warsaw")).date()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-year", type=int, default=2017)
    args = parser.parse_args()
    if not 2000 <= args.from_year <= today.year:
        parser.error("--from-year must be between 2000 and the current year")
    months = [(year, month) for year in range(args.from_year, today.year + 1)
              for month in range(1, 13) if (year, month) <= (today.year, today.month)]
    previous = json.loads(OUTPUT.read_text()) if OUTPUT.exists() else {"series": []}
    series = {entry["code"]: entry for entry in previous["series"]}
    refreshed = 0
    failures = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        for entry, error in pool.map(fetch_month, months):
            if entry:
                entry["verifiedAt"] = today.isoformat()
                series[entry["code"]] = entry
                refreshed += 1
            else:
                failures.append(error)
                print(error, file=sys.stderr, flush=True)
    if refreshed == 0:
        raise SystemExit("No verified series; existing snapshot left unchanged.")
    catalog = dict(updatedAt=today.isoformat(),
                   series=sorted(series.values(), key=lambda item: item["saleStart"], reverse=True))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".tmp")
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(OUTPUT)
    print(f"Refreshed {refreshed} series; {len(failures)} unavailable; saved {len(series)} total.")


if __name__ == "__main__":
    main()
