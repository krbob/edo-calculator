# Operacje i wdrożenie

## Charakterystyka runtime

EDO Calculator jest bezstanową aplikacją JVM/Ktor nasłuchującą na porcie 8080. Nie wymaga bazy danych, wolumenów ani sekretów. Jedyny zapis runtime to odtwarzalny cache klienta HTTP w `/tmp/edo-calculator/http-cache`.

Obraz jest budowany przez Jib na wieloarchitekturnym `gcr.io/distroless/java21-debian13:nonroot`, działa jako `65532:65532` i nie zawiera powłoki, `curl` ani menedżera pakietów. Nie definiuje Docker `HEALTHCHECK`.

## Niezmienny obraz

CI publikuje tag `sha-<krótki-commit>` i kompatybilnościowy `latest`. Produkcja powinna rozwiązać zaakceptowany obraz do digestu manifestu i zapisać pełną referencję:

```text
ghcr.io/krbob/edo-calculator@sha256:<64 znaki szesnastkowe>
```

Przykładowy fragment Compose wymaga jawnego ustawienia tej wartości:

```yaml
services:
  edo-calculator:
    image: ${EDO_CALCULATOR_IMAGE:?ustaw obraz ghcr.io/krbob/edo-calculator@sha256:<digest>}
    restart: unless-stopped
    environment:
      TZ: Europe/Warsaw
    ports:
      - "127.0.0.1:8080:8080"
```

`TZ` ujednolica czas procesu i logów. Reguły biznesowe i tak jawnie używają `Europe/Warsaw`.

## Reverse proxy i probe'y

| Ścieżka | Znaczenie | Sukces | Uwagi |
|---|---|---|---|
| `/healthz` | liveness procesu | `200 text/plain`, `ok` | nie odpytuje zależności |
| `/readyz` | gotowość lokalnego grafu DI | `200 text/plain`, `ready` | celowo nie odpytuje GUS |
| `/metrics` | scrape Prometheus | `200 text/plain` | tylko prywatna sieć monitoringu |

Brak GUS w readiness jest zamierzony: chwilowa awaria zewnętrznego dostawcy nie powinna wyłączać wszystkich replik. Gotowość nie gwarantuje więc, że zimne zapytanie wymagające CPI zakończy się sukcesem.

Traefik może wykonywać healthcheck usługi bez narzędzi wewnątrz obrazu:

```yaml
labels:
  - traefik.http.services.edo.loadbalancer.server.port=8080
  - traefik.http.services.edo.loadbalancer.healthcheck.path=/readyz
  - traefik.http.services.edo.loadbalancer.healthcheck.interval=10s
  - traefik.http.services.edo.loadbalancer.healthcheck.timeout=3s
```

Publiczny router nie powinien dopuszczać `/metrics`. Błędy wygenerowane przez proxy nie są odpowiedziami aplikacji i nie muszą zawierać jej JSON-u błędu ani `X-Request-ID`.

## GUS i warstwy cache

Usługa korzysta z `https://api-sdp.stat.gov.pl` bez klucza API. Dane przechodzą przez dwie warstwy cache:

1. Ktor `HttpCache` zapisuje odpowiedzi transportowe w `/tmp/edo-calculator/http-cache` zgodnie z nagłówkami HTTP.
2. Cache domenowy przechowuje znormalizowane dane roku w pamięci procesu. Kompletne lata historyczne nie wygasają; dane bieżące lub niekompletne są ponownie oceniane wraz z upływem czasu i mają bazowy TTL jednej godziny.

Cache domenowy nie jest rozgrzewany podczas startu. Jeśli odświeżenie nie powiedzie się, istniejący wpis może zostać użyty jako `stale_fallback`. Po restarcie oba cache mogą być zimne, a pierwsze złożone zapytanie może być wyraźnie droższe.

Od danych za 2026 rok integracja GUS korzysta z nowego endpointu zmiennych. Dla roku uruchamia odczyty miesięcy, które są ograniczone wspólnym limitem 5 żądań na sekundę i 5 równoległych wywołań. Duże odpowiedzi GUS mogą chwilowo rozszerzyć heap JVM i podnieść RSS kontenera; sam wysoki poziom pamięci po takim żądaniu nie dowodzi wycieku.

## Timeouty i retry

| Odcinek | Budżet |
|---|---:|
| Pojedyncze połączenie EDO → GUS | connect 2 s |
| Pojedyncza odpowiedź/socket GUS | 3 s |
| Cała operacja domenowa EDO | 8 s |
| Zalecany timeout klienta | więcej niż 8 s; Portfolio używa 10 s |

Retry obejmuje wyłącznie odpowiedzi `429` i `5xx`, maksymalnie trzy próby. Bazowe opóźnienia wynoszą 100 i 200 ms; poprawne `Retry-After` jest respektowane do limitu jednej sekundy. Timeouty transportowe i błędy nieretryowalne nie są ponawiane.

Przekroczenie budżetu, awaria dostawcy lub brak CPI zwracają `503` z kodem odpowiednio `CPI_PROVIDER_UNAVAILABLE` albo `CPI_DATA_UNAVAILABLE` i `retryable=true`. Klient powinien stosować ograniczony backoff, a nie ciasną pętlę retry.

## Metryki i logi

Najważniejsze serie Prometheus:

- `edo_http_server_requests_seconds` — timer HTTP z etykietami `method`, `route` i `status`;
- `edo_http_server_requests_active` — liczba aktywnych żądań;
- `edo_gus_fetch_seconds` — logiczne pobranie roku według atrybutu, endpointu i wyniku;
- `edo_gus_cache_requests_total` — `hit`, `load`, `stale_fallback`, `load_error`, `cancelled`;
- `edo_gus_retries_total` — retry według `rate_limited` lub `server_error`;
- standardowe serie JVM Micrometera.

Przykładowe zapytania:

```promql
sum by (route, status) (rate(edo_http_server_requests_seconds_count[5m]))
histogram_quantile(0.95, sum by (le, route) (rate(edo_http_server_requests_seconds_bucket[5m])))
sum by (result) (rate(edo_gus_cache_requests_total[5m]))
```

Nieznane trasy są agregowane do ograniczonej etykiety, a niestandardowe metody do `OTHER`. `/metrics` nie jest rejestrowane przez timer ani access log. `/healthz` i `/readyz` również są wyciszone w access logu.

Bezpieczny wejściowy `X-Request-ID` jest zachowywany; brakujący albo niepoprawny identyfikator jest zastępowany UUID i trafia do MDC logów jako `requestId`.

## Pamięć JVM

Repozytorium nie narzuca runtime `-Xmx` ani limitu pamięci kontenera. `org.gradle.jvmargs` dotyczy wyłącznie procesu budowania. JVM może zachować rozszerzony heap po dużej odpowiedzi GUS, mimo że ilość żywych danych po GC jest dużo mniejsza.

Przed ustawieniem limitu należy obserwować co najmniej:

- RSS oraz peak kontenera;
- `jvm_memory_used_bytes` według obszaru;
- `jvm_gc_live_data_size_bytes`;
- czas i liczbę GC;
- liczbę `503` oraz czas `edo_gus_fetch_seconds`.

`JAVA_TOOL_OPTIONS` i limit kontenera należy dobrać po teście zimnego cache i zapytania obejmującego dane od 2026 roku. Zbyt niski limit może zamienić przejściowy peak w OOM.

## Bezpieczeństwo i rollout

API nie ma uwierzytelnienia, ponieważ udostępnia wyłącznie obliczenia bez operacji zapisu. Warstwa wdrożeniowa odpowiada za TLS, ograniczenie `/metrics`, ewentualne limity ruchu i kontrolę sieci.

Minimalna procedura wydania:

1. Poczekaj na zielone testy, skany obrazu i publikację wieloarchitekturną.
2. Zapisz digest manifestu opublikowanego obrazu.
3. Zmień referencję Compose na nowy digest i odtwórz usługę.
4. Sprawdź `/healthz`, `/readyz` i deterministyczną wycenę z pierwszego okresu.
5. Wykonaj pojedynczy canary wymagający GUS i sprawdź metryki `503`, retry oraz pamięci.
6. W razie regresji wróć do poprzedniego digestu; aplikacja nie wymaga migracji danych.
