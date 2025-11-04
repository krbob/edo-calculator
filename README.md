# edo-calculator

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/krbob/edo-calculator/ci.yml)

Serwer HTTP napisany w Ktorze, który udostępnia obliczenia dla obligacji skarbowych EDO oraz wskaźniki inflacyjne GUS. Poniżej znajdziesz opis wszystkich dostępnych endpointów (4), wymagane parametry oraz przykładowe odpowiedzi.

## Uruchomienie

1. Upewnij się, że masz zainstalowanego Dockera.
2. Uruchom kontener: `docker run --rm -p 8080:8080 ghcr.io/krbob/edo-calculator:latest`.
3. Serwer będzie dostępny pod adresem `http://localhost:8080`.

## Konwencje odpowiedzi

- Wszystkie endpointy zwracają `Content-Type: application/json` i korzystają z pretty-print.
- Wartości dziesiętne są serializowane jako tekst (`"123.45"`) – wynika to z dedykowanego serializatora `BigDecimal`.

## Endpointy

### GET `/edo/value`

- **Opis:** oblicza aktualną wartość obligacji EDO (stan „na dziś”) dla wskazanego dnia zakupu oraz parametrów obligacji.
- **Parametry zapytania:**
  | nazwa             | typ      | wymagane | opis                                                   |
  |-------------------|----------|----------|--------------------------------------------------------|
  | `purchaseYear`    | integer  | tak      | rok zakupu obligacji                                   |
  | `purchaseMonth`   | integer  | tak      | miesiąc zakupu (1–12)                                  |
  | `purchaseDay`     | integer  | tak      | dzień zakupu (1–31, zgodnie z kalendarzem)            |
  | `firstPeriodRate` | decimal  | tak      | kupon procentowy w pierwszym okresie (np. `2.70`)     |
  | `margin`          | decimal  | tak      | marża dodawana po pierwszym okresie                    |
  | `principal`       | decimal  | nie      | nominał inwestycji w PLN (domyślnie `100.00`)          |

> Jeśli nie przekażesz parametru `principal`, zostanie użyta wartość domyślna `100.00` PLN (dotyczy również końcówki `/edo/value/at`).

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/edo/value?purchaseYear=2019&purchaseMonth=7&purchaseDay=15&firstPeriodRate=2.70&margin=1.25&principal=1000"
```

#### Przykładowa odpowiedź

```json
{
    "purchaseDate": "2019-07-15",
    "asOf": "2025-11-04",
    "firstPeriodRate": "2.70",
    "margin": "1.25",
    "principal": "1000.00",
    "edoValue": {
        "totalValue": "1571.74",
        "totalAccruedInterest": "571.74",
        "periods": [
            {
                "index": 1,
                "startDate": "2019-07-15",
                "endDate": "2020-07-15",
                "daysInPeriod": 366,
                "daysElapsed": 366,
                "ratePercent": "2.70",
                "inflationPercent": null,
                "interestAccrued": "27.00",
                "value": "1027.00"
            },
            {
                "index": 2,
                "startDate": "2020-07-15",
                "endDate": "2021-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 365,
                "ratePercent": "4.15",
                "inflationPercent": "2.90",
                "interestAccrued": "42.62",
                "value": "1069.62"
            },
            {
                "index": 3,
                "startDate": "2021-07-15",
                "endDate": "2022-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 365,
                "ratePercent": "5.95",
                "inflationPercent": "4.70",
                "interestAccrued": "63.64",
                "value": "1133.26"
            },
            {
                "index": 4,
                "startDate": "2022-07-15",
                "endDate": "2023-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 365,
                "ratePercent": "15.15",
                "inflationPercent": "13.90",
                "interestAccrued": "171.69",
                "value": "1304.95"
            },
            {
                "index": 5,
                "startDate": "2023-07-15",
                "endDate": "2024-07-15",
                "daysInPeriod": 366,
                "daysElapsed": 366,
                "ratePercent": "14.25",
                "inflationPercent": "13.00",
                "interestAccrued": "185.96",
                "value": "1490.91"
            },
            {
                "index": 6,
                "startDate": "2024-07-15",
                "endDate": "2025-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 365,
                "ratePercent": "3.75",
                "inflationPercent": "2.50",
                "interestAccrued": "55.91",
                "value": "1546.82"
            },
            {
                "index": 7,
                "startDate": "2025-07-15",
                "endDate": "2026-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 112,
                "ratePercent": "5.25",
                "inflationPercent": "4.00",
                "interestAccrued": "24.92",
                "value": "1571.74"
            }
        ]
    }
}
```

### GET `/edo/value/at`

- **Opis:** identyczne obliczenia jak w `/edo/value`, ale dla zadanego dnia rozliczenia (`asOf`), który może różnić się od bieżącej daty systemowej.
- **Parametry zapytania:** wszystkie parametry z `/edo/value` oraz dodatkowo:
  | nazwa        | typ     | wymagane | opis                                         |
  |--------------|---------|----------|----------------------------------------------|
  | `asOfYear`   | integer | tak      | rok, dla którego ma zostać wyliczona wartość |
  | `asOfMonth`  | integer | tak      | miesiąc (1–12)                               |
  | `asOfDay`    | integer | tak      | dzień (1–31)                                 |

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/edo/value/at?purchaseYear=2019&purchaseMonth=7&purchaseDay=15&asOfYear=2022&asOfMonth=5&asOfDay=1&firstPeriodRate=2.70&margin=1.25&principal=1000"
```

#### Przykładowa odpowiedź

```json
{
    "purchaseDate": "2019-07-15",
    "asOf": "2022-05-01",
    "firstPeriodRate": "2.70",
    "margin": "1.25",
    "principal": "1000.00",
    "edoValue": {
        "totalValue": "1120.19",
        "totalAccruedInterest": "120.19",
        "periods": [
            {
                "index": 1,
                "startDate": "2019-07-15",
                "endDate": "2020-07-15",
                "daysInPeriod": 366,
                "daysElapsed": 366,
                "ratePercent": "2.70",
                "inflationPercent": null,
                "interestAccrued": "27.00",
                "value": "1027.00"
            },
            {
                "index": 2,
                "startDate": "2020-07-15",
                "endDate": "2021-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 365,
                "ratePercent": "4.15",
                "inflationPercent": "2.90",
                "interestAccrued": "42.62",
                "value": "1069.62"
            },
            {
                "index": 3,
                "startDate": "2021-07-15",
                "endDate": "2022-07-15",
                "daysInPeriod": 365,
                "daysElapsed": 290,
                "ratePercent": "5.95",
                "inflationPercent": "4.70",
                "interestAccrued": "50.57",
                "value": "1120.19"
            }
        ]
    }
}
```

### GET `/inflation/since`

- **Opis:** zwraca złożony mnożnik inflacji (CPI) od wskazanego miesiąca do najnowszego miesiąca dostępnego w bazie GUS. Miesiąc końcowy (`until`) jest traktowany jako granica wyłączna – mnożnik obejmuje dane do ostatniego dnia poprzedniego miesiąca.
- **Parametry zapytania:**
  | nazwa   | typ     | wymagane | opis                |
  |---------|---------|----------|---------------------|
  | `year`  | integer | tak      | rok startowy        |
  | `month` | integer | tak      | miesiąc startowy (1–12) |

> Pola `from` i `until` mają format `YYYY-MM`; `until` wskazuje miesiąc wyłączony z obliczeń (granica wyłączna).
#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/inflation/since?year=2020&month=1"
```

#### Przykładowa odpowiedź

```json
{
    "from": "2020-01",
    "until": "2025-10",
    "multiplier": "1.472925"
}
```

### GET `/inflation/between`

- **Opis:** zwraca mnożnik inflacji pomiędzy dwoma wskazanymi miesiącami. Wartość `endMonth` działa jako granica wyłączna – okres obejmuje miesiące od `startMonth` włącznie do miesiąca poprzedzającego `endMonth`.
- **Parametry zapytania:**
  | nazwa        | typ     | wymagane | opis                             |
  |--------------|---------|----------|----------------------------------|
  | `startYear`  | integer | tak      | rok początkowy                   |
  | `startMonth` | integer | tak      | miesiąc początkowy (1–12)        |
  | `endYear`    | integer | tak      | rok końcowy                      |
  | `endMonth`   | integer | tak      | miesiąc końcowy (1–12)           |

> `endMonth` i `endYear` wyznaczają pierwszy miesiąc wyłączony z obliczeń (granica wyłączna); mnożnik obejmuje miesiące do poprzedniego włącznie. Pola `from` i `until` mają format `YYYY-MM`.
#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/inflation/between?startYear=2020&startMonth=1&endYear=2022&endMonth=12"
```

#### Przykładowa odpowiedź

```json
{
    "from": "2020-01",
    "until": "2022-12",
    "multiplier": "1.296949"
}
```

## Obsługa błędów

- W przypadku błędnych parametrów serwer zwraca kod `400 Bad Request`.
- Brak danych CPI skutkuje `503 Service Unavailable`, a nieoczekiwane błędy `500 Internal Server Error`.
- Wszystkie odpowiedzi błędów mają postać:

```json
{
    "error": "Invalid request parameters."
}
```

Zwracana wiadomość (`error`) zależy od rodzaju problemu (np. brak parametru, błędny format, brak danych GUS).
