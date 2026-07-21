# Rozwój i utrzymanie

## Wymagania

- JDK 21;
- Docker tylko do budowy lub uruchamiania obrazu;
- połączenie z Maven Central podczas pierwszego rozwiązania zależności.

Projekt zawsze uruchamia Gradle przez dołączony wrapper.

## Struktura

| Moduł | Zależności i odpowiedzialność |
|---|---|
| `core` | czas biznesowy, fabryka JSON i wspólne moduły DI |
| `client` | niezależna infrastruktura klienta HTTP, timeout, cache, retry i limiter |
| `domain` | zależy od `core`; zawiera use case'y oraz modele bez routingu Ktor |
| `inflation-gus` | zależy od `core`, `client` i `domain`; implementuje port CPI przez GUS |
| projekt główny | składa moduły w aplikację Ktor i udostępnia HTTP, logi oraz metryki |

Reguły finansowe powinny pozostawać w `domain`, szczegóły GUS w `inflation-gus`, a routing jedynie parsować wejście i mapować wynik.

## Najczęstsze komendy

Uruchomienie aplikacji:

```bash
./gradlew run
```

Testy, analiza statyczna i artefakty JVM:

```bash
./gradlew test detekt assemble
```

Sprawdzenie układu dokumentacji, lokalnych linków i bezpiecznych przykładów obrazów:

```bash
python3 scripts/validate-docs.py
```

Lokalny obraz dla architektury hosta:

```bash
./gradlew jibDockerBuild --image=edo-calculator:local
docker run --rm -p 8080:8080 edo-calculator:local
```

Agregowany CycloneDX 1.6 SBOM zależności produkcyjnych:

```bash
./gradlew cyclonedxBom
```

Plik powstaje w `build/reports/cyclonedx/edo-calculator.cdx.json`. Nie zawiera zależności testowych ani losowego numeru seryjnego, a `metadata.timestamp` jest normalizowany do epoki, dzięki czemu wynik dla tego samego źródła jest powtarzalny.

## Blokady zależności

Wszystkie rozwiązywalne konfiguracje Gradle są blokowane w repozytoryjnych `*gradle.lockfile`. Po świadomej zmianie wersji:

```bash
./gradlew resolveAndLockAll --write-locks
git status --short -- '*gradle.lockfile'
```

Należy przejrzeć i zacommitować wynik. Lockfile nie powinny być poprawiane ręcznie. CI ponownie wykonuje rozwiązanie i odrzuca niespójny stan.

Wrapper ma zapisany SHA-256 dystrybucji i włączoną walidację URL. Jawny toolchain wymusza Java 21 we wszystkich modułach.

## OpenAPI jako źródło kontraktu

[`openapi/edo-calculator-v1.yaml`](../openapi/edo-calculator-v1.yaml) jest jedynym reference API. README i dokumenty tematyczne nie powinny kopiować pełnych tabel parametrów ani dynamicznych odpowiedzi GUS.

Przy zmianie API należy:

1. zmienić najpierw implementację i OpenAPI;
2. zaktualizować testy routingu oraz `OpenApiContractTest`;
3. dla nowego przykładu wybrać deterministyczny przypadek pierwszego okresu, niewymagający GUS;
4. uruchomić `./gradlew test detekt assemble`;
5. ocenić kompatybilność aliasów legacy i klienta Portfolio.

`OpenApiContractTest` pilnuje listy tras, parametrów, statusów, modeli serializacji, enumów, ograniczeń dziesiętnych oraz statycznych przykładów. Nie należy dodawać do kontraktu odpowiedzi zależnych od bieżącej daty ani aktualnej publikacji GUS.

Jeśli potrzebny jest zewnętrzny generator klienta, jego obraz musi być przypięty digestem zaakceptowanej wersji, na przykład przez zmienną:

```bash
export OPENAPI_GENERATOR_IMAGE='openapitools/openapi-generator-cli@sha256:<zatwierdzony-digest>'
docker run --rm -v "$PWD:/local" "$OPENAPI_GENERATOR_IMAGE" generate \
  -i /local/openapi/edo-calculator-v1.yaml \
  -g kotlin \
  -o /local/build/generated/edo-client
```

Katalog `build/` jest ignorowany i wygenerowanego klienta nie należy commitować.

## Integracja z Portfolio

Portfolio korzysta wyłącznie z wersjonowanych tras `/v1` i przechowuje sprawdzony snapshot kontraktu EDO w `apps/api/contracts/upstream/edo-calculator-v1.yaml` własnego repozytorium. Snapshot jest celowo niezależny od sąsiedniego checkoutu.

Po zmianie kontraktu konsumowanego przez Portfolio trzeba w jego repozytorium:

1. skopiować dokładny OpenAPI z zaakceptowanego commita EDO;
2. zaktualizować rewizję źródłową i SHA-256 w `upstream-contracts.properties`;
3. uruchomić `./gradlew clean checkUpstreamContracts generateUpstreamContracts test detekt build` w `apps/api`;
4. przejrzeć mapowania klienta i testy konsumenckie.

Zmiany wyłącznie opisowe można odłożyć do następnego świadomego upgrade'u snapshotu, ale nie należy przedstawiać starego pliku jako bieżącego kontraktu producenta.

## CI i publikacja

Workflow `.github/workflows/ci.yml` ma dwa etapy:

1. `test` — sprawdza dokumentację i lockfile, uruchamia testy/detekt/assemble, weryfikuje powtarzalność SBOM, skanuje zależności i system obrazu, buduje kontener oraz wykonuje deterministyczny smoke;
2. `publish` — po zielonym pushu do `main` publikuje manifesty `amd64` i `arm64` do GHCR.

Rzeczywisty GUS jest sprawdzany osobnym `Live GUS canary (non-blocking)`. Jego chwilowa awaria jest widoczna w runie, ale nie blokuje publikacji zweryfikowanego obrazu.

Publikowane są tagi `sha-<krótki-commit>` i `latest`. Tag SHA ułatwia identyfikację źródła, lecz środowisko produkcyjne powinno zapisać digest manifestu zwrócony przez rejestr.

Skany Trivy blokują naprawialne podatności `HIGH` i `CRITICAL`. SBOM jest artefaktem CI przechowywanym przez 14 dni. Akcje GitHub, wersja skanera i bazowy obraz Jib są przypięte.

## Renovate

Renovate tworzy dojrzałe PR-y zależności na bieżąco, bez limitu równoczesnych branchy,
PR-ów ani limitu godzinowego. Istniejące branche może aktualizować i ponownie testować
przez cały miesiąc. Wszystkie rodzaje aktualizacji — również major, Gradle, GitHub
Actions, skaner i obraz bazowy — kwalifikują się do squash automerge po zielonym
wymaganym CI. Renovate wykonuje merge wyłącznie przez pierwsze trzy dni miesiąca;
natywny automerge GitHuba pozostaje wyłączony, aby nie ominąć tego okna.

CI każdego PR-a zależności sprawdza lockfile, pełne testy, SBOM i oba skany
podatności. Aktualizacja digestu obrazu bazowego przechodzi również smoke kontenera.

## Utrzymanie dokumentacji

| Informacja | Źródło prawdy |
|---|---|
| Endpointy, parametry, odpowiedzi, błędy | `openapi/edo-calculator-v1.yaml` |
| Reguły finansowe i limity | kod `domain` oraz `docs/calculation-model.md` |
| Timeouty, retry, cache | kod `client`/`inflation-gus` oraz `docs/operations.md` |
| Wersje narzędzi i zależności | wrapper, `libs.versions.toml`, workflow i lockfile |
| Obraz produkcyjny | digest zapisany w konfiguracji wdrożenia |

Nie należy wpisywać do dokumentacji odpowiedzi „na dziś”, bieżących wartości CPI ani digestu przyszłego wydania. Takie dane szybko tracą aktualność.
