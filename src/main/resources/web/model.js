const numberFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 6 });
const dateFormat = new Intl.DateTimeFormat('pl-PL', { timeZone: 'UTC', day: '2-digit', month: '2-digit', year: 'numeric' });

export function warsawToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now);
  const get = (type) => parts.find((part) => part.type === type).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

export function formatDate(value) {
  return dateFormat.format(new Date(`${value}T00:00:00Z`));
}

// Monetary arithmetic and formatting use decimal strings/BigInt, never floating point.
export function money(value) {
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(String(value));
  if (!match) throw new Error('Nieprawidłowa kwota w odpowiedzi kalkulatora.');
  return `${match[1]}${numberFormat.format(BigInt(match[2]))},${(match[3] || '').padEnd(2, '0')} zł`;
}

export function percent(value) {
  return `${numberFormat.format(Number(value))}%`;
}

export function returnPercent(interest, principal) {
  const cents = (value) => {
    const [whole, fraction = ''] = value.split('.');
    return BigInt(`${whole}${fraction.padEnd(2, '0')}`);
  };
  const capital = cents(principal);
  if (capital === 0n) return '—';
  const hundredths = (cents(interest) * 10000n + capital / 2n) / capital;
  return `${hundredths / 100n},${String(hundredths % 100n).padStart(2, '0')}%`;
}

function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function rate(value, label) {
  const normalized = value.trim().replace(',', '.');
  if (!/^\d{1,4}(?:\.\d{1,6})?$/.test(normalized) || Number(normalized) > 1000) {
    throw new Error(`${label}: wpisz liczbę od 0 do 1000, do 6 miejsc po przecinku.`);
  }
  return normalized;
}

export function parseInputs(raw, series, today = warsawToday()) {
  if (!raw.manual && !series) throw new Error('Wybierz serię z katalogu albo włącz wpisywanie warunków ręcznie.');
  if (!validDate(raw.purchaseDate) || raw.purchaseDate < '2000-01-01') throw new Error('Podaj poprawną datę zakupu od 2000 roku.');
  if (!validDate(raw.asOf) || raw.asOf > today) throw new Error('Dzień wyceny nie może być późniejszy niż dzisiaj.');
  if (raw.purchaseDate > raw.asOf) throw new Error('Dzień wyceny nie może być wcześniejszy niż data zakupu.');
  if (!raw.manual && (raw.purchaseDate < series.saleStart || raw.purchaseDate > series.saleEnd)) {
    throw new Error(`Seria ${series.code} była w sprzedaży od ${formatDate(series.saleStart)} do ${formatDate(series.saleEnd)}. Wybierz datę z tego zakresu.`);
  }
  if (!/^\d{1,11}$/.test(raw.quantity) || BigInt(raw.quantity) < 1n || BigInt(raw.quantity) > 10000000000n) {
    throw new Error('Podaj całkowitą liczbę obligacji od 1 do 10 000 000 000.');
  }
  return {
    purchaseDate: raw.purchaseDate, asOf: raw.asOf,
    firstPeriodRate: rate(raw.manual ? raw.firstRate : series.firstPeriodRate, 'Oprocentowanie pierwszego roku'),
    margin: rate(raw.manual ? raw.margin : series.margin, 'Marża'),
    principal: `${BigInt(raw.quantity) * 100n}.00`,
    title: raw.manual ? 'Własne warunki EDO' : series.code,
  };
}

function setDate(params, prefix, value) {
  const [year, month, day] = value.split('-');
  params.set(`${prefix}Year`, year);
  params.set(`${prefix}Month`, month);
  params.set(`${prefix}Day`, day);
}

export function requestParams(input, historyEnd) {
  const params = new URLSearchParams({ firstPeriodRate: input.firstPeriodRate, margin: input.margin, principal: input.principal });
  setDate(params, 'purchase', input.purchaseDate);
  if (historyEnd) {
    setDate(params, 'from', input.purchaseDate);
    setDate(params, 'to', historyEnd);
  } else {
    setDate(params, 'asOf', input.asOf);
  }
  return params;
}

export function currentPeriod(response) {
  if (response.status === 'MATURED') return null;
  return response.edoValue.periods.find((period) => period.startDate <= response.asOf && response.asOf < period.endDate) ?? null;
}

export function historyEnd(response) {
  // Stop at maturity: an old, matured bond must not exceed the API's 4000-point limit.
  return response.asOf < response.maturityDate ? response.asOf : response.maturityDate;
}

export function apiErrorMessage(error) {
  const messages = {
    CPI_DATA_UNAVAILABLE: 'Brakuje danych inflacji GUS potrzebnych do tej wyceny. Spróbuj ponownie później.',
    CPI_PROVIDER_UNAVAILABLE: 'GUS jest chwilowo niedostępny. Spróbuj ponownie za chwilę.',
    INVALID_REQUEST: 'Kalkulator odrzucił parametry. Sprawdź daty, oprocentowanie i liczbę obligacji.',
    INTERNAL_ERROR: 'Kalkulator nie mógł wykonać obliczenia. Spróbuj ponownie później.',
  };
  const message = messages[error.errorCode] || 'Nie udało się pobrać wyniku z kalkulatora.';
  return error.requestId ? `${message} Identyfikator: ${error.requestId}` : message;
}
