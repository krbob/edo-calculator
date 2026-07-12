# edo-calculator

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/krbob/edo-calculator/ci.yml)

Serwer HTTP napisany w Ktorze, który udostępnia obliczenia dla obligacji skarbowych EDO oraz wskaźniki inflacyjne GUS. Poniżej znajdziesz opis wszystkich dostępnych endpointów domenowych (6), wymagane parametry oraz przykładowe odpowiedzi.

## Uruchomienie

1. Upewnij się, że masz zainstalowanego Dockera.
2. Uruchom kontener: `docker run --rm -p 8080:8080 ghcr.io/krbob/edo-calculator:latest`.
3. Serwer będzie dostępny pod adresem `http://localhost:8080`.
4. Liveness procesu jest dostępne pod `GET /healthz`, readiness grafu zależności aplikacji pod `GET /readyz`, a metryki Prometheus pod `GET /metrics`. Readiness nie odpytuje zewnętrznego GUS, aby jego awaria nie wyłączała wszystkich replik. Obraz Jib nie definiuje wbudowanego Docker `HEALTHCHECK`.

Alternatywnie, możesz użyć Docker Compose:

```yaml
services:
  edo-calculator:
    image: ghcr.io/krbob/edo-calculator:latest
    ports:
      - "8080:8080"
    restart: unless-stopped
```

Zapisz powyższy fragment jako `docker-compose.yml` i uruchom `docker compose up -d`.

## Łańcuch dostaw i powtarzalność builda

Build używa jawnego toolchainu Java 21 LTS we wszystkich modułach. Wrapper Gradle 9.6.1 weryfikuje pobraną dystrybucję przez zapisany SHA-256, a wszystkie rozwiązywalne konfiguracje Gradle mają wersje utrwalone w repozytoryjnych plikach `*gradle.lockfile`. Po świadomej zmianie zależności należy odświeżyć i zacommitować lockfile:

```bash
./gradlew resolveAndLockAll --write-locks
git status --short -- '*gradle.lockfile'
```

CI uruchamia tę samą komendę i kończy się błędem, jeśli wygenerowany stan różni się od wersji w repozytorium. Nie należy ręcznie edytować lockfile.

Zagregowany CycloneDX 1.6 SBOM dla zależności produkcyjnych powstaje poleceniem:

```bash
./gradlew cyclonedxBom
```

Wynik trafia do ignorowanego pliku `build/reports/cyclonedx/edo-calculator.cdx.json`. Nie zawiera losowego numeru seryjnego, danych konkretnego systemu CI ani zależności testowych. Pole `metadata.timestamp` ma wartość epoki reprodukowalnego builda (`1970-01-01T00:00:00Z`), dlatego dwa uruchomienia dla tego samego źródła dają identyczne bajty i SHA-256. Rzeczywisty czas wygenerowania pozostaje w metadanych runu oraz artefaktu GitHub Actions.

CI publikuje SBOM jako artefakt na 14 dni i skanuje zarówno jego zależności runtime, jak i pakiety systemowe lokalnie zbudowanego obrazu. Skan SBOM działa offline względem zewnętrznych repozytoriów Maven i korzysta z dokładnych PURL zapisanych w dokumencie. Biblioteki JVM nie są ponownie skanowane z warstw obrazu, dzięki czemu CI nie pobiera prawie gigabajtowej bazy identyfikacji artefaktów Java i nie dubluje gate'u SBOM. Gate obejmuje podatności `HIGH` i `CRITICAL`, dla których istnieje poprawka; `ignore-unfixed` zapobiega blokowaniu zmian problemami bez dostępnej ścieżki naprawy. Wersja Trivy i kod akcji są przypięte, podobnie jak wszystkie pozostałe akcje GitHub.

Obraz Jib bazuje na wieloarchitekturnym `gcr.io/distroless/java21-debian13:nonroot` przypiętym digestem indeksu OCI, działa jako `65532:65532`, a czas utworzenia warstw to `EPOCH`. Renovate śledzi digest przez dedykowany manager, ale aktualizacje obrazu bazowego, Gradle, GitHub Actions, skanera, Kotlin/Ktor/Koin/Logback/Micrometer/CycloneDX/Detekt oraz wszystkie wersje major wymagają ręcznego review. Automerge jest ograniczony do niekrytycznych patchy Gradle starszych niż 7 dni i następuje dopiero po zielonym CI.

## Konwencje odpowiedzi

- Endpointy domenowe zwracają `Content-Type: application/json`; probe'y i `/metrics` używają formatu tekstowego właściwego dla danego endpointu. Produkcyjne odpowiedzi JSON są kompaktowe, aby ograniczyć koszt dużych historii; przykłady poniżej są sformatowane wyłącznie dla czytelności.
- Wartości dziesiętne są serializowane jako tekst (`"123.45"`) – wynika to z dedykowanego serializatora `BigDecimal`.
- Pojęcia „dzisiaj” i „bieżący miesiąc” używają polskiej strefy biznesowej `Europe/Warsaw`, niezależnie od strefy hosta lub kontenera.
- Odpowiedzi wyceny zawierają datę zapadalności `maturityDate` oraz status `ACTIVE` lub `MATURED`. Od dnia zapadalności wartość nie nalicza już odsetek.
- Każda odpowiedź zawiera `X-Request-ID`. Bezpieczny identyfikator klienta jest zachowywany, a brakujący lub niepoprawny zastępowany identyfikatorem serwera i dodawany do kontekstu logów jako `requestId`.
- Błędy zachowują kompatybilne pole `error` i dodatkowo zwracają stabilne `errorCode`, flagę `retryable` oraz `requestId` zgodny z nagłówkiem odpowiedzi.
- Ten sam kontrakt błędu obejmuje walidację, niedostępność CPI, nieznane trasy, niedozwolone metody i nieobsłużone wyjątki aplikacji.

## OpenAPI

Utrzymywany kontrakt API znajduje się w pliku [`openapi/edo-calculator-v1.yaml`](openapi/edo-calculator-v1.yaml). Obejmuje wszystkie kanoniczne trasy `/v1`, probe'y `/healthz` i `/readyz`, scrape `/metrics`, parametry, schematy odpowiedzi oraz stabilny model błędu. Rozszerzenie `x-legacy-alias` wskazuje odpowiadającą trasę bez prefiksu.

Specyfikacja nie jest publikowana przez aplikację jako Swagger UI. Test `OpenApiContractTest` parsuje ją oficjalnym Swagger Parserem i pilnuje zgodności tras, parametrów, modeli serializacji, kodów błędów i aliasów legacy. Przykład wygenerowania klienta Kotlin do ignorowanego katalogu `build/`:

```bash
docker run --rm -v "$PWD:/local" openapitools/openapi-generator-cli generate \
  -i /local/openapi/edo-calculator-v1.yaml \
  -g kotlin \
  -o /local/build/generated/edo-client
```

## Obserwowalność

`GET /metrics` zwraca format tekstowy Prometheus. Endpoint powinien być dostępny wyłącznie z sieci monitoringu; aplikacja nie dodaje do metryk `requestId`, surowego URL ani segmentu nieznanej trasy. Sam scrape `/metrics` jest wyłączony z timera HTTP i access logu (bieżący scrape pozostaje widoczny w gauge aktywnych żądań), nieznane trasy współdzielą etykietę `route="/{...}"`, a niestandardowe metody HTTP wartość `method="OTHER"`.

Najważniejsze serie:

- `edo_http_server_requests_seconds` – timer HTTP z ograniczonymi etykietami `method`, `route` i `status`; eksportuje count, sum oraz histogram z progami SLO 50 ms–8 s i maksymalną oczekiwaną wartością 10 s,
- `edo_gus_fetch_seconds` – czas logicznego pobrania roku GUS według `attribute`, `endpoint` i `outcome`, bez etykiety roku,
- `edo_gus_cache_requests_total` – wyniki `hit`, `load`, `stale_fallback`, `load_error` i `cancelled`,
- `edo_gus_retries_total` – dodatkowe próby według powodu `rate_limited` lub `server_error`,
- standardowe metryki JVM i `edo_http_server_requests_active`.

Przykładowe zapytania PromQL:

```promql
sum by (route, status) (rate(edo_http_server_requests_seconds_count[5m]))
histogram_quantile(0.95, sum by (le, route) (rate(edo_http_server_requests_seconds_bucket[5m])))
sum by (result) (rate(edo_gus_cache_requests_total[5m]))
```

## Budżet timeout i retry

Budżety są ułożone od callerów do najdalszej zależności tak, aby wewnętrzna usługa zakończyła pracę przed klientem:

| odcinek | budżet | zachowanie |
|---|---:|---|
| Portfolio → EDO: value/inflation | 10 s | timeout klienta Portfolio |
| Portfolio → EDO: history | 10 s | wspólny budżet klienta Portfolio: 8 s operacji EDO + 2 s na serializację i transfer |
| pojedyncza operacja domenowa EDO | 8 s | przekroczenie zwraca `503`, `CPI_PROVIDER_UNAVAILABLE`, `retryable=true` |
| pojedyncza próba EDO → GUS | connect 2 s, request/socket 3 s | zawsze podporządkowana 8-sekundowemu budżetowi operacji |
| retry GUS | maks. 3 próby | tylko `429` i `5xx`; opóźnienia 100/200 ms, a `Retry-After` jest ograniczony do 1 s |

Limit GUS 5 żądań/s i 5 równoległych wywołań również zużywa budżet 8 s. Trafienie cache nie wykonuje requestu GUS, a `stale_fallback` może zwrócić ostatnie dane po nieudanym odświeżeniu. EDO nie wykonuje retry dla timeoutów transportowych ani błędów nieretrywalnych, co zapobiega mnożeniu prób pomiędzy warstwami.

## Endpointy

Kanoniczne, wersjonowane endpointy są dostępne z prefiksem `/v1` (np. `/v1/edo/value` i `/v1/inflation/monthly`). Dotychczasowe ścieżki bez prefiksu pozostają kompatybilnymi aliasami na czas migracji klientów.

### GET `/v1/edo/value`

- **Opis:** oblicza aktualną wartość obligacji EDO (stan „na dziś”) dla wskazanego dnia zakupu oraz parametrów obligacji.
- **Parametry zapytania:**
  | nazwa             | typ      | wymagane | opis                                                   |
  |-------------------|----------|----------|--------------------------------------------------------|
  | `purchaseYear`    | integer  | tak      | rok zakupu obligacji                                   |
  | `purchaseMonth`   | integer  | tak      | miesiąc zakupu (1–12)                                  |
  | `purchaseDay`     | integer  | tak      | dzień zakupu (1–31, zgodnie z kalendarzem)            |
  | `firstPeriodRate` | decimal  | tak      | kupon procentowy w pierwszym okresie (np. `2.70`)     |
  | `margin`          | decimal  | tak      | marża dodawana po pierwszym okresie                    |
  | `principal`       | decimal  | nie      | analityczna baza wyceny w PLN (domyślnie `100.00`)     |

> Jeśli nie przekażesz parametru `principal`, zostanie użyta wartość domyślna `100.00` PLN (dotyczy również końcówki `/v1/edo/value/at`).
> Jeśli przekażesz `principal`, musi to być poprawna liczba dziesiętna, w przeciwnym razie serwer zwróci `400 Bad Request`.
> Obsługiwane są daty zakupu od 2000 roku, principal do `1000000000000`, stopy i marża do `1000%`, maksymalnie 18 cyfr precyzji i 6 miejsc dziesiętnych. Dłuższe lub bardziej precyzyjne wartości są odrzucane kodem `400` przed rozpoczęciem obliczeń.
> Pola procentowe odpowiedzi zachowują dokładną precyzję stopy użytej w obliczeniu. Nie są niezależnie zaokrąglane do dwóch miejsc; do dwóch miejsc zaokrąglane są dopiero raportowane wartości pieniężne.

Oficjalny nominał jednej obligacji EDO wynosi 100 PLN, jednak `principal` jest świadomie zachowany jako agregowana, analityczna baza wyceny pro-rata. Endpoint nie sprawdza wykonalności zakupu ani wielokrotności 100 PLN: zarówno `/v1`, jak i aliasy legacy od początku dopuszczają dowolną nieujemną wartość, a zawężenie złamałoby istniejący kontrakt. Portfolio pobiera wartość jednostki dla `principal=100` i skaluje ją ilością; klient modelujący rzeczywiste zlecenie powinien osobno wymagać całkowitej liczby obligacji. Dla kwot niebędących wielokrotnością 100 wynik jest wyceną analityczną z zaokrągleniem kwot agregatu, a nie gwarantowaną kwotą rozliczenia emitenta. Zasady pojedynczej obligacji opisuje [oficjalna oferta EDO](https://www.obligacjeskarbowe.pl/oferta-obligacji/obligacje-10-letnie-edo/).

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/edo/value?purchaseYear=2019&purchaseMonth=7&purchaseDay=15&firstPeriodRate=2.70&margin=1.25&principal=1000"
```

#### Przykładowa odpowiedź

```json
{
    "purchaseDate": "2019-07-15",
    "asOf": "2025-11-04",
    "maturityDate": "2029-07-15",
    "status": "ACTIVE",
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

### GET `/v1/edo/value/at`

- **Opis:** identyczne obliczenia jak w `/v1/edo/value`, ale dla zadanego dnia rozliczenia (`asOf`), który może różnić się od bieżącej daty systemowej.
- **Parametry zapytania:** wszystkie parametry z `/v1/edo/value` oraz dodatkowo:
  | nazwa        | typ     | wymagane | opis                                         |
  |--------------|---------|----------|----------------------------------------------|
  | `asOfYear`   | integer | tak      | rok, dla którego ma zostać wyliczona wartość |
  | `asOfMonth`  | integer | tak      | miesiąc (1–12)                               |
  | `asOfDay`    | integer | tak      | dzień (1–31)                                 |

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/edo/value/at?purchaseYear=2019&purchaseMonth=7&purchaseDay=15&asOfYear=2022&asOfMonth=5&asOfDay=1&firstPeriodRate=2.70&margin=1.25&principal=1000"
```

#### Przykładowa odpowiedź

```json
{
    "purchaseDate": "2019-07-15",
    "asOf": "2022-05-01",
    "maturityDate": "2029-07-15",
    "status": "ACTIVE",
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

### GET `/v1/edo/history`

- **Opis:** zwraca dzienną historię wartości EDO dla wskazanego zakupu. Endpoint wykorzystuje tę samą logikę wyceny co `/v1/edo/value` i `/v1/edo/value/at`, ale generuje serię punktów dzień po dniu.
- Harmonogram maksymalnie dziesięciu okresów odsetkowych i potrzebne stopy inflacji są przygotowywane raz dla całego zakresu; kolejne punkty powstają liniowo bez ponawiania pełnej wyceny i zapytań o CPI dla każdego dnia.
- **Parametry zapytania:** wszystkie parametry z `/v1/edo/value` oraz opcjonalnie:
  | nazwa        | typ     | wymagane | opis                                                           |
  |--------------|---------|----------|----------------------------------------------------------------|
  | `fromYear`   | integer | nie      | rok początku zakresu historii                                  |
  | `fromMonth`  | integer | nie      | miesiąc początku zakresu (1–12)                                |
  | `fromDay`    | integer | nie      | dzień początku zakresu (1–31)                                  |
  | `toYear`     | integer | nie      | rok końca zakresu historii                                     |
  | `toMonth`    | integer | nie      | miesiąc końca zakresu (1–12)                                   |
  | `toDay`      | integer | nie      | dzień końca zakresu (1–31)                                     |

> Jeśli nie podasz `from*`, historia zaczyna się od dnia zakupu. Jeśli nie podasz `to*`, historia kończy się na bieżącej dacie w strefie `Europe/Warsaw`.
> Parametry `from*` i `to*` muszą być podane kompletnie (rok, miesiąc i dzień), jeśli chcesz ich użyć.
> Pojedyncza odpowiedź historii może zawierać maksymalnie 4000 dziennych punktów.

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/edo/history?purchaseYear=2023&purchaseMonth=1&purchaseDay=1&fromYear=2023&fromMonth=1&fromDay=2&toYear=2023&toMonth=1&toDay=4&firstPeriodRate=7.25&margin=1.25&principal=100"
```

```bash
curl "http://localhost:8080/v1/edo/history?purchaseYear=2023&purchaseMonth=1&purchaseDay=1&firstPeriodRate=7.25&margin=1.25&principal=100"
```

#### Przykładowa odpowiedź

```json
{
    "purchaseDate": "2023-01-01",
    "from": "2023-01-02",
    "until": "2023-01-04",
    "firstPeriodRate": "7.25",
    "margin": "1.25",
    "principal": "100.00",
    "points": [
        {
            "date": "2023-01-02",
            "totalValue": "100.02",
            "totalAccruedInterest": "0.02"
        },
        {
            "date": "2023-01-03",
            "totalValue": "100.04",
            "totalAccruedInterest": "0.04"
        },
        {
            "date": "2023-01-04",
            "totalValue": "100.06",
            "totalAccruedInterest": "0.06"
        }
    ]
}
```

### GET `/v1/inflation/since`

- **Opis:** zwraca złożony mnożnik inflacji (CPI) od wskazanego miesiąca do najnowszego miesiąca dostępnego w bazie GUS. Miesiąc końcowy (`until`) jest traktowany jako granica wyłączna – mnożnik obejmuje dane do ostatniego dnia poprzedniego miesiąca.
- **Parametry zapytania:**
  | nazwa   | typ     | wymagane | opis                |
  |---------|---------|----------|---------------------|
  | `year`  | integer | tak      | rok startowy        |
  | `month` | integer | tak      | miesiąc startowy (1–12) |

> Pola `from` i `until` mają format `YYYY-MM`; `until` wskazuje miesiąc wyłączony z obliczeń (granica wyłączna).
> Miesiąc startowy musi być wcześniejszy niż bieżący miesiąc kalendarzowy.
> Obsługiwane są dane od 2010 roku, a pojedynczy zakres nie może przekraczać 360 miesięcy.
#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/inflation/since?year=2020&month=1"
```

#### Przykładowa odpowiedź

```json
{
    "from": "2020-01",
    "until": "2025-10",
    "multiplier": "1.472925"
}
```

### GET `/v1/inflation/between`

- **Opis:** zwraca mnożnik inflacji pomiędzy dwoma wskazanymi miesiącami. Wartość `endMonth` działa jako granica wyłączna – okres obejmuje miesiące od `startMonth` włącznie do miesiąca poprzedzającego `endMonth`.
- **Parametry zapytania:**
  | nazwa        | typ     | wymagane | opis                             |
  |--------------|---------|----------|----------------------------------|
  | `startYear`  | integer | tak      | rok początkowy                   |
  | `startMonth` | integer | tak      | miesiąc początkowy (1–12)        |
  | `endYear`    | integer | tak      | rok końcowy                      |
  | `endMonth`   | integer | tak      | miesiąc końcowy (1–12)           |

> `endMonth` i `endYear` wyznaczają pierwszy miesiąc wyłączony z obliczeń (granica wyłączna); mnożnik obejmuje miesiące do poprzedniego włącznie. Pola `from` i `until` mają format `YYYY-MM`.
> `endMonth`/`endYear` muszą wskazywać miesiąc po `startMonth` oraz nie mogą wybiegać w przyszłość względem bieżącego miesiąca.
#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/inflation/between?startYear=2020&startMonth=1&endYear=2022&endMonth=12"
```

#### Przykładowa odpowiedź

```json
{
    "from": "2020-01",
    "until": "2022-12",
    "multiplier": "1.296949"
}
```

### GET `/v1/inflation/monthly`

- **Opis:** zwraca miesięczną serię mnożników CPI dla zadanego zakresu miesięcy. Każdy punkt reprezentuje inflację dla pojedynczego miesiąca, a `until` jest granicą wyłączną.
- **Parametry zapytania:**
  | nazwa        | typ     | wymagane | opis                             |
  |--------------|---------|----------|----------------------------------|
  | `startYear`  | integer | tak      | rok początkowy                   |
  | `startMonth` | integer | tak      | miesiąc początkowy (1–12)        |
  | `endYear`    | integer | tak      | rok końcowy                      |
  | `endMonth`   | integer | tak      | miesiąc końcowy (1–12)           |

> Zakres działa jak w `/v1/inflation/between`: `startMonth` jest włączony, `endMonth` wyłączony. Odpowiedź zawiera po jednym punkcie dla każdego miesiąca należącego do zakresu.

#### Przykładowe zapytanie

```bash
curl "http://localhost:8080/v1/inflation/monthly?startYear=2025&startMonth=12&endYear=2026&endMonth=3"
```

#### Przykładowa odpowiedź

```json
{
    "from": "2025-12",
    "until": "2026-03",
    "points": [
        {
            "month": "2025-12",
            "multiplier": "1.002000"
        },
        {
            "month": "2026-01",
            "multiplier": "1.003000"
        },
        {
            "month": "2026-02",
            "multiplier": "1.004000"
        }
    ]
}
```

## Obsługa błędów

- W przypadku błędnych parametrów serwer zwraca kod `400 Bad Request`.
- Brak danych CPI oraz chwilowa niedostępność dostawcy GUS skutkują `503 Service Unavailable`, a nieoczekiwane błędy `500 Internal Server Error`.
- Wszystkie odpowiedzi błędów mają postać:

```json
{
    "error": "Invalid request parameters.",
    "errorCode": "INVALID_REQUEST",
    "retryable": false,
    "requestId": "4a903cf4-6098-4500-a17e-df46e3895f34"
}
```

Zwracana wiadomość (`error`) zależy od rodzaju problemu (np. brak parametru, błędny format, brak danych GUS). Pełny zestaw stabilnych wartości `errorCode` jest częścią schematu `ApiErrorResponse` w OpenAPI.
