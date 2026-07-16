# Model obliczeniowy

Ten dokument opisuje założenia implementacji. Kontrakt HTTP znajduje się wyłącznie w [`openapi/edo-calculator-v1.yaml`](../openapi/edo-calculator-v1.yaml).

## Zakres modelu

Usługa wycenia dziesięcioletnią obligację EDO na podstawie parametrów przekazanych przez klienta. Nie rozpoznaje automatycznie serii obligacji i nie pobiera jej warunków emisyjnych. Klient podaje:

- datę zakupu;
- oprocentowanie pierwszego okresu w procentach;
- marżę dla kolejnych okresów w punktach procentowych;
- opcjonalny `principal`, domyślnie `100.00` PLN.

`principal` jest analityczną bazą wyceny pro-rata. Nie musi być wielokrotnością oficjalnego nominału jednej obligacji, czyli 100 PLN. Portfolio pobiera wycenę dla 100 PLN i skaluje ją liczbą jednostek. Klient modelujący rzeczywiste zlecenie powinien osobno sprawdzać całkowitą liczbę obligacji.

Model nie uwzględnia podatku, opłaty za przedterminowy wykup, ograniczeń sprzedażowych ani indywidualnych zasad rozliczenia emitenta. Wynik jest wyceną analityczną, a nie gwarantowaną kwotą wypłaty. Aktualne warunki produktu publikuje [serwis obligacji skarbowych](https://www.obligacjeskarbowe.pl/oferta-obligacji/obligacje-10-letnie-edo/).

## Ograniczenia wejścia

| Wartość | Ograniczenie |
|---|---|
| Data zakupu EDO | Rok 2000 lub późniejszy; nie może być późniejsza od dnia wyceny |
| `asOf` i koniec historii | Nie mogą wybiegać w przyszłość |
| `principal` | Od 0 do `1000000000000` PLN |
| Stopa pierwszego okresu i marża | Od 0 do `1000%` |
| Liczby dziesiętne | Maksymalnie 18 cyfr precyzji i 6 miejsc po przecinku |
| Historia dzienna | Maksymalnie 4000 punktów |
| Dane CPI | Od 2010 roku, maksymalnie 360 miesięcy w jednym zakresie |

Wartość `from` historii wcześniejsza od daty zakupu jest podnoszona do daty zakupu. Niekompletne grupy parametrów daty oraz zakres odwrócony są odrzucane kodem `400`.

## Okresy i kapitalizacja EDO

Harmonogram zawiera najwyżej dziesięć rocznych okresów liczonych od daty zakupu:

1. W pierwszym okresie używana jest przekazana stopa `firstPeriodRate`.
2. Dla każdego kolejnego okresu pobierany jest z GUS roczny wskaźnik CPI dla miesiąca przypadającego dwa miesiące przed początkiem okresu.
3. Inflacja jest obliczana jako `mnożnik CPI - 1`. Wartość ujemna jest zastępowana zerem.
4. Roczna stopa okresu jest sumą nieujemnej inflacji i przekazanej marży.
5. Odsetki pełnego okresu są kapitalizowane i stają się bazą kolejnego okresu.

W trwającym okresie odsetki narastają liniowo według faktycznej liczby dni:

```text
odsetki okresu = kapitał na początku okresu × stopa roczna × dni, które upłynęły / dni w okresie
```

Lata przestępne mają zatem 366 dni. W dniu początku okresu liczba dni, które upłynęły, wynosi zero. Od dnia zapadalności, czyli po dziesięciu latach od zakupu, status ma wartość `MATURED` i model nie nalicza dalszych odsetek.

## Precyzja i zaokrąglenia

- obliczenia pośrednie korzystają z `MathContext.DECIMAL64`;
- wejściowy `principal` jest normalizowany do dwóch miejsc metodą `HALF_UP`;
- raportowane kwoty pieniężne są zaokrąglane do dwóch miejsc metodą `HALF_UP`;
- stopy procentowe zachowują precyzję wartości faktycznie użytej w obliczeniu i mają co najmniej dwa miejsca;
- skumulowane i miesięczne mnożniki CPI są raportowane z sześcioma miejscami metodą `HALF_EVEN`;
- wartości dziesiętne w JSON są serializowane jako tekst przez `BigDecimal.toPlainString()`.

Zaokrąglenie raportu nie zmienia wartości pośrednich używanych do kapitalizacji kolejnych okresów.

## Historia EDO

Historia używa tego samego harmonogramu i tych samych reguł co pojedyncza wycena. Harmonogram oraz wymagane odczyty CPI są przygotowywane raz dla zakresu, a następnie powstaje po jednym punkcie na każdy dzień, łącznie z obiema granicami.

Domyślnie historia zaczyna się w dniu zakupu i kończy w bieżącym dniu strefy `Europe/Warsaw`. `to` nie może być późniejsze niż bieżący dzień.

## Obliczenia CPI

Zakresy miesięczne są półotwarte: miesiąc początkowy jest włączony, a końcowy wyłączony. Przykładowo zakres `2025-01`–`2025-04` obejmuje styczeń, luty i marzec.

- skumulowany mnożnik jest iloczynem mnożników miesięcznych;
- seria miesięczna zwraca jeden punkt dla każdego miesiąca zakresu;
- wariant `since` cofa granicę końcową od bieżącego miesiąca do najnowszego kompletnego zakresu dostępnego w GUS;
- brak wymaganych danych zwraca `CPI_DATA_UNAVAILABLE`, a awaria dostawcy `CPI_PROVIDER_UNAVAILABLE`.

Pojęcia „dzisiaj” i „bieżący miesiąc” zawsze używają strefy `Europe/Warsaw`, niezależnie od systemowej strefy hosta.
