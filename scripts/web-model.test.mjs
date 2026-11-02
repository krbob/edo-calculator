import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { currentPeriod, historyEnd, money, parseInputs, requestParams, returnPercent, warsawToday } from '../src/main/resources/web/model.js';

const series = { code: 'EDO0130', saleStart: '2020-01-01', saleEnd: '2020-01-31', firstPeriodRate: '2.70', margin: '1.50' };
const raw = { manual: false, purchaseDate: '2020-01-15', asOf: '2020-02-15', quantity: '3', firstRate: '99', margin: '99' };

test('catalog terms and integer bond quantity become exact API parameters', () => {
  const input = parseInputs(raw, series, '2020-02-15');
  assert.equal(input.principal, '300.00');
  assert.equal(input.firstPeriodRate, '2.70');
  assert.equal(input.margin, '1.50');
  const params = requestParams(input);
  assert.equal(params.get('purchaseDay'), '15');
  assert.equal(params.get('asOfMonth'), '02');
  assert.equal(params.get('principal'), '300.00');
  assert.equal(parseInputs({ ...raw, quantity: '10000000000' }, series).principal, '1000000000000.00');
});

test('reject unknown series, mismatched sale dates, future dates and invalid quantities', () => {
  assert.throws(() => parseInputs(raw, undefined));
  for (const changes of [
    { purchaseDate: '2020-02-01' }, { purchaseDate: '2020-02-30' },
    { asOf: '2020-01-14' }, { asOf: '2020-02-16' },
    { quantity: '0' }, { quantity: '1.5' }, { quantity: '10000000001' }, { quantity: '1e3' },
  ]) assert.throws(() => parseInputs({ ...raw, ...changes }, series, '2020-02-15'), JSON.stringify(changes));
});

test('manual terms accept a Polish decimal comma without rounding rates', () => {
  const manual = { ...raw, manual: true, firstRate: '2,123456', margin: '0,50' };
  assert.equal(parseInputs(manual).firstPeriodRate, '2.123456');
  assert.equal(parseInputs(manual).margin, '0.50');
  for (const value of ['-1', '1000.01', '2.1234567', 'NaN', '']) {
    assert.throws(() => parseInputs({ ...manual, firstRate: value }));
  }
});

test('anniversary uses the new period; maturity has no current rate and caps history', () => {
  const periods = [
    { startDate: '2020-01-15', endDate: '2021-01-15', ratePercent: '2.70' },
    { startDate: '2021-01-15', endDate: '2022-01-15', ratePercent: '4.00' },
  ];
  const response = { status: 'ACTIVE', asOf: '2021-01-15', maturityDate: '2030-01-15', edoValue: { periods } };
  assert.equal(currentPeriod(response), periods[1]);
  assert.equal(currentPeriod({ ...response, asOf: '2021-01-14' }), periods[0]);
  assert.equal(historyEnd(response), '2021-01-15');
  const matured = { ...response, status: 'MATURED', asOf: '2040-01-15' };
  assert.equal(currentPeriod(matured), null);
  assert.equal(historyEnd(matured), '2030-01-15');
  const params = requestParams(parseInputs(raw, series), historyEnd(matured));
  assert.equal(params.get('toYear'), '2030');
  assert.equal(params.get('asOfYear'), null);
});

test('Warsaw midnight is independent of the browser time zone', () => {
  assert.equal(warsawToday(new Date('2026-01-01T23:30:00Z')), '2026-01-02');
  assert.equal(warsawToday(new Date('2026-07-01T22:30:00Z')), '2026-07-02');
});

test('money and percentage displays preserve cents even beyond Number precision', () => {
  assert.equal(money('123456789012345678.01').replace(/\s/g, ''), '123456789012345678,01zł');
  assert.equal(returnPercent('0.01', '100.00'), '0,01%');
  assert.equal(returnPercent('10.00', '100.00'), '10,00%');
  assert.equal(returnPercent('1.00', '3.00'), '33,33%');
  assert.equal(returnPercent('0.00', '0.00'), '—');
});

test('bundled catalog has distinct, coherent series with official provenance', async () => {
  const catalog = JSON.parse(await readFile(new URL('../src/main/resources/web/series.json', import.meta.url)));
  assert.ok(catalog.series.length > 0);
  assert.equal(new Set(catalog.series.map((entry) => entry.code)).size, catalog.series.length);
  for (const entry of catalog.series) {
    assert.match(entry.code, /^EDO\d{4}$/);
    assert.equal(entry.code.slice(3, 5), entry.saleStart.slice(5, 7));
    assert.equal(Number(entry.code.slice(5)), (Number(entry.saleStart.slice(0, 4)) + 10) % 100);
    assert.equal(entry.saleStart.slice(0, 7), entry.saleEnd.slice(0, 7));
    assert.equal(new URL(entry.sourceUrl).hostname, 'www.obligacjeskarbowe.pl');
    assert.ok(entry.sourceUrl.endsWith(`/${entry.code.toLowerCase()}/`));
    assert.ok(entry.verifiedAt <= catalog.updatedAt);
    assert.doesNotThrow(() => parseInputs({ ...raw, purchaseDate: entry.saleStart, asOf: entry.saleEnd }, entry, entry.saleEnd));
  }
});

test('vendored ecosystem tokens retain the recorded digest', async () => {
  const base = new URL('../src/main/resources/web/vendor/', import.meta.url);
  const manifest = JSON.parse(await readFile(new URL('tokens.manifest.json', base)));
  const css = await readFile(new URL('tokens.css', base));
  assert.equal(createHash('sha256').update(css).digest('hex'), manifest.sha256);
});
