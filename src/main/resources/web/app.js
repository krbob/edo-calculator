import { apiErrorMessage, currentPeriod, formatDate, historyEnd, money, parseInputs, percent, requestParams, returnPercent, warsawToday } from './model.js';
import { renderChart } from './chart.js';

const $ = (id) => document.getElementById(id);
const form = $('bond-form');
let catalog = [];
let request = null;
let revision = 0;

function selectedSeries() {
  return catalog.find((entry) => entry.code === $('series').value.trim().toUpperCase());
}

function clearResults() {
  revision++;
  request?.abort();
  $('results').hidden = true;
  $('empty-state').hidden = false;
  $('error').hidden = true;
  $('form-status').textContent = '';
  $('calculate').disabled = false;
  $('result-area').setAttribute('aria-busy', 'false');
}

function updateTerms(resetDate = false) {
  const series = selectedSeries();
  const manual = $('manual').checked;
  $('series').disabled = manual;
  $('first-rate').readOnly = !manual;
  $('margin').readOnly = !manual;
  $('source-link').hidden = manual || !series;
  $('purchase-date').min = manual || !series ? '2000-01-01' : series.saleStart;
  $('purchase-date').max = manual || !series ? warsawToday() : [series.saleEnd, warsawToday()].sort()[0];
  if (manual) {
    $('series-help').textContent = 'Własne parametry z listu emisyjnego. Wynik będzie oznaczony jako wycena ręczna.';
  } else if (series) {
    $('first-rate').value = series.firstPeriodRate.replace('.', ',');
    $('margin').value = series.margin.replace('.', ',');
    $('series-help').textContent = `Sprzedaż: ${formatDate(series.saleStart)}–${formatDate(series.saleEnd)}`;
    $('source-link').href = series.sourceUrl;
    $('source-link').textContent = `Warunki emisji ↗ · sprawdzone ${formatDate(series.verifiedAt)}`;
    if (resetDate || $('purchase-date').value < series.saleStart || $('purchase-date').value > series.saleEnd) {
      $('purchase-date').value = series.saleStart;
    }
  } else {
    $('first-rate').value = '';
    $('margin').value = '';
    $('series-help').textContent = 'Wybierz serię z listy. Brak serii? Wpisz warunki ręcznie.';
  }
}

function updatePrincipal() {
  const quantity = $('quantity').value;
  $('principal-hint').textContent = /^\d{1,11}$/.test(quantity)
    ? `Kapitał nominalny: ${money(`${BigInt(quantity) * 100n}.00`)}`
    : 'Jedna obligacja ma nominał 100 zł.';
}

async function fetchJson(url, signal) {
  const response = await fetch(url, { signal, headers: { Accept: 'application/json' } });
  let body;
  try { body = await response.json(); } catch { throw new Error('Serwer zwrócił nieprawidłową odpowiedź. Spróbuj ponownie później.'); }
  if (!response.ok) throw new Error(apiErrorMessage(body));
  return body;
}

function showValue(response, input) {
  const period = currentPeriod(response);
  $('result-title').textContent = `${input.title} · ${formatDate(response.asOf)}`;
  $('result-status').textContent = response.status === 'MATURED' ? 'Po terminie wykupu' : `Okres ${period.index} z 10`;
  $('interest-value').textContent = money(response.edoValue.totalAccruedInterest);
  $('return-value').textContent = `+${returnPercent(response.edoValue.totalAccruedInterest, response.principal)} od dnia zakupu`;
  $('rate-value').textContent = period ? percent(period.ratePercent) : 'Zakończone';
  $('rate-detail').textContent = period
    ? `${formatDate(period.startDate)}–${formatDate(period.endDate)} · ${period.inflationPercent === null ? 'stała stopa pierwszego roku' : `inflacja ${percent(period.inflationPercent)} + marża ${percent(response.margin).replace('%', ' p.p.')}`}`
    : `Od ${formatDate(response.maturityDate)} odsetki już nie narastają.`;
  $('total-value').textContent = money(response.edoValue.totalValue);
  $('principal-value').textContent = money(response.principal);
  $('maturity-value').textContent = formatDate(response.maturityDate);
  $('period-count').textContent = `${response.edoValue.periods.length} z 10`;
  $('periods').replaceChildren(...response.edoValue.periods.map((entry) => {
    const row = document.createElement('tr');
    const active = entry === period;
    if (active) row.className = 'current';
    const cells = [`${entry.index}${active ? ' · w toku' : ''}`, `${formatDate(entry.startDate)}–${formatDate(entry.endDate)}`, percent(entry.ratePercent), money(entry.interestAccrued)];
    for (const value of cells) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    }
    return row;
  }));
  $('results').hidden = false;
  $('empty-state').hidden = true;
}

async function calculate(event) {
  event?.preventDefault();
  clearResults();
  const version = revision;
  let input;
  try {
    input = parseInputs({
      manual: $('manual').checked, purchaseDate: $('purchase-date').value, asOf: $('as-of').value,
      firstRate: $('first-rate').value, margin: $('margin').value, quantity: $('quantity').value,
    }, selectedSeries());
  } catch (error) {
    $('error').textContent = error.message;
    $('error').hidden = false;
    return;
  }
  const controller = new AbortController();
  request = controller;
  const timeout = setTimeout(() => controller.abort(new Error('Przekroczono czas oczekiwania. Spróbuj ponownie.')), 90000);
  $('calculate').disabled = true;
  $('result-area').setAttribute('aria-busy', 'true');
  $('form-status').textContent = 'Obliczanie wartości i pobieranie danych inflacji…';
  $('chart').hidden = true;
  $('history-status').textContent = 'Ładowanie historii…';
  try {
    const value = await fetchJson(`/v1/edo/value/at?${requestParams(input)}`, controller.signal);
    if (version !== revision) return;
    showValue(value, input);
    $('form-status').textContent = `Wycena gotowa na ${formatDate(value.asOf)}.`;
    try {
      const history = await fetchJson(`/v1/edo/history?${requestParams(input, historyEnd(value))}`, controller.signal);
      if (version !== revision) return;
      renderChart($('chart'), history.points);
      $('history-status').textContent = '';
    } catch (error) {
      if (version !== revision) return;
      $('history-status').textContent = `Nie udało się wyświetlić historii. ${error instanceof TypeError ? 'Sprawdź połączenie i oblicz ponownie.' : error.message}`;
    }
  } catch (error) {
    if (version !== revision) return;
    $('error').textContent = error instanceof TypeError ? 'Nie można połączyć się z kalkulatorem. Sprawdź połączenie i spróbuj ponownie.' : error.message;
    $('error').hidden = false;
    $('form-status').textContent = '';
  } finally {
    clearTimeout(timeout);
    if (version === revision) {
      $('calculate').disabled = false;
      $('result-area').setAttribute('aria-busy', 'false');
    }
  }
}

form.addEventListener('submit', calculate);
form.addEventListener('input', (event) => {
  clearResults();
  if (event.target.id === 'series' || event.target.id === 'manual') updateTerms(event.target.id === 'series');
  if (event.target.id === 'quantity') updatePrincipal();
  $('as-of').min = $('purchase-date').value || '2000-01-01';
});

async function initialize() {
  $('purchase-date').value = warsawToday();
  $('purchase-date').max = warsawToday();
  $('as-of').value = warsawToday();
  $('as-of').max = warsawToday();
  const initialRevision = revision;
  try {
    const snapshot = await fetchJson('/ui/series.json', AbortSignal.timeout(15000));
    catalog = snapshot.series.filter((entry) => entry.saleStart <= warsawToday());
    const groups = new Map();
    for (const entry of catalog) {
      const year = entry.saleStart.slice(0, 4);
      if (!groups.has(year)) {
        const group = document.createElement('optgroup');
        group.label = `Emisje ${year}`;
        groups.set(year, group);
      }
      const option = document.createElement('option');
      option.value = entry.code;
      option.textContent = `${entry.code} · ${entry.saleStart.slice(5, 7)}.${year}`;
      groups.get(year).append(option);
    }
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'Wybierz serię';
    $('series').replaceChildren(placeholder, ...groups.values());
    $('catalog-note').textContent = `Katalog: ${catalog.length} serii · aktualizacja ${formatDate(snapshot.updatedAt)}. Brakującą serię możesz sprawdzić ręcznie.`;
    if (revision === initialRevision && catalog.length) {
      $('series').value = catalog[0].code;
      updateTerms(true);
      await calculate();
    } else {
      updateTerms();
    }
  } catch {
    if (revision === initialRevision) {
      $('manual').checked = true;
      updateTerms();
    }
    $('catalog-note').textContent = 'Nie udało się wczytać katalogu. Możesz wpisać warunki emisji ręcznie.';
  }
}

initialize();
