# EDO Calculator

[![CI Pipeline](https://img.shields.io/github/actions/workflow/status/krbob/edo-calculator/ci.yml?branch=main&label=CI)](https://github.com/krbob/edo-calculator/actions/workflows/ci.yml)

Niewielka, bezstanowa usługa HTTP w Ktorze do analitycznej wyceny dziesięcioletnich obligacji skarbowych EDO oraz obliczeń polskiej inflacji na podstawie danych GUS. Usługa nie przechowuje portfela ani transakcji i nie wymaga bazy danych.

Kanoniczne API korzysta z prefiksu `/v1`. Pełnym źródłem kontraktu endpointów, parametrów, odpowiedzi i błędów jest [OpenAPI 3.1](openapi/edo-calculator-v1.yaml). Trasy bez `/v1` pozostają aliasami kompatybilności dla starszych klientów, ale nowy kod nie powinien z nich korzystać.

## Szybki start

Wymagane są JDK 21, Docker i plugin Docker Compose. Jib buduje obraz bez osobnego `Dockerfile`, a dołączony
[plik Compose](docker-compose.yml) wystawia usługę wyłącznie na lokalnym interfejsie:

```bash
./gradlew jibDockerBuild --image=edo-calculator:local
docker compose up --detach

curl --fail http://127.0.0.1:8080/healthz
curl --fail http://127.0.0.1:8080/readyz
```

Zatrzymaj usługę poleceniem `docker compose down`. Obraz distroless nie zawiera powłoki ani `curl`, dlatego probe'y
wykonuje host, a Compose nie deklaruje pozornego healthchecka kontenera.

Obrazy wieloarchitekturne są również publikowane do GHCR. Trwałe wdrożenie powinno wskazywać niezmienny digest
manifestu z zakończonego sukcesem joba `Publish image`; przykład znajduje się w
[dokumencie operacyjnym](docs/operations.md#niezmienny-obraz).

Deterministyczny przykład wyceny z pierwszego okresu odsetkowego nie wymaga danych CPI:

```bash
curl --get 'http://localhost:8080/v1/edo/value/at' \
  --data-urlencode 'purchaseYear=2020' \
  --data-urlencode 'purchaseMonth=1' \
  --data-urlencode 'purchaseDay=1' \
  --data-urlencode 'asOfYear=2020' \
  --data-urlencode 'asOfMonth=1' \
  --data-urlencode 'asOfDay=2' \
  --data-urlencode 'firstPeriodRate=5.00' \
  --data-urlencode 'margin=2.00' \
  --data-urlencode 'principal=100.00'
```

Zweryfikowana odpowiedź tego przykładu jest częścią kontraktu OpenAPI i testów. Zapytania obejmujące kolejne okresy zależą od dostępności danych GUS i mogą zwrócić retryowalny błąd `503`.

## Uruchomienie lokalne bez Dockera

Wymagany jest JDK 21. Projekt korzysta z wrappera Gradle, więc osobna instalacja Gradle nie jest potrzebna.

```bash
./gradlew run
```

Pełna lokalna weryfikacja:

```bash
./gradlew test detekt assemble
```

Testy jednostkowe i kontraktowe nie wymagają działającego GUS. Rzeczywisty GUS jest sprawdzany w CI osobnym, nieblokującym canary.

## Konfiguracja runtime

| Obszar | Zachowanie |
|---|---|
| Port HTTP | `8080` |
| Strefa biznesowa | Zawsze `Europe/Warsaw`, niezależnie od `TZ` hosta lub kontenera |
| Źródło CPI | Publiczne API GUS `api-sdp.stat.gov.pl` |
| Uwierzytelnienie | Brak; kontrolę dostępu zapewnia warstwa wdrożeniowa |
| Trwały stan | Brak; cache HTTP w `/tmp/edo-calculator/http-cache` jest odtwarzalny |
| Sekrety | Brak wymaganych sekretów i kluczy API |

Aktualna aplikacja nie udostępnia zmiennych środowiskowych do zmiany tych wartości. `TZ=Europe/Warsaw` może ujednolicić czas logów, ale nie steruje regułami obliczeń.

## Probe'y i obserwowalność

- `GET /healthz` — liveness procesu, odpowiedź `ok`;
- `GET /readyz` — sprawdzenie lokalnego grafu zależności, odpowiedź `ready`; celowo nie odpytuje GUS;
- `GET /metrics` — metryki Prometheus, przeznaczone wyłącznie dla prywatnej sieci monitoringu.

Obraz distroless nie zawiera narzędzia `curl` i nie definiuje wbudowanego Docker `HEALTHCHECK`. Probe'y powinien wykonywać reverse proxy lub orkiestrator. `X-Request-ID` jest dodawany do odpowiedzi wygenerowanych przez aplikację; odpowiedzi proxy pozostają poza tym kontraktem.

Szczegóły wdrożenia, cache, limitów GUS, timeoutów, retry, metryk i diagnostyki pamięci opisuje [dokument operacyjny](docs/operations.md).

## Moduły

| Moduł | Odpowiedzialność |
|---|---|
| `core` | czas biznesowy i serializacja wspólna |
| `client` | klient HTTP, cache transportowy, timeouty, retry i rate limiting |
| `domain` | reguły wyceny EDO, historia i obliczenia CPI |
| `inflation-gus` | mapowanie oraz cache danych GUS |
| aplikacja główna | Ktor, routing, DI, błędy, logi i metryki |

Opis reguł finansowych znajduje się w [modelu obliczeniowym](docs/calculation-model.md), a komendy developerskie, CI i zasady aktualizacji zależności w [przewodniku rozwoju](docs/development.md).

## Najważniejsze zasady kontraktu

- kwoty i wartości dziesiętne w JSON są tekstami, aby nie tracić precyzji;
- daty używają ISO 8601, a zakresy miesięcy są półotwarte: początek włączony, koniec wyłączony;
- odpowiedzi wyceny zawierają `maturityDate` i status `ACTIVE` albo `MATURED`;
- błędy domenowe i routingu mają stabilne `errorCode`, `retryable` oraz `requestId`;
- awaria `/readyz` jest wyjątkiem operacyjnym i zwraca `text/plain`, nie domenowy JSON błędu;
- pojedyncza historia EDO jest ograniczona do 4000 punktów, a zakres CPI do 360 miesięcy.

Szczegółowe ograniczenia i przykłady pozostają w [kontrakcie OpenAPI](openapi/edo-calculator-v1.yaml), aby nie utrzymywać równoległej dokumentacji endpointów.
