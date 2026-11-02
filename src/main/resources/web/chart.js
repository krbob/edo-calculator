import { formatDate, money } from './model.js';

const ns = 'http://www.w3.org/2000/svg';
function svgNode(name, attributes = {}, text) {
  const node = document.createElementNS(ns, name);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
  if (text !== undefined) node.textContent = text;
  return node;
}

export function renderChart(container, points) {
  if (!points.length) throw new Error('Brak punktów historii.');
  container.replaceChildren();
  // Floating point is used only for chart geometry; displayed amounts come from the API.
  const values = points.map((point) => Number(point.totalValue));
  const low = Math.min(...values);
  const high = Math.max(...values);
  const span = high - low || Math.max(low * 0.01, 1);
  const min = high === low ? low - span / 2 : low;
  const x = (i) => 92 + (points.length === 1 ? 0.5 : i / (points.length - 1)) * 548;
  const y = (value) => 186 - (value - min) / span * 156;
  const svg = svgNode('svg', { viewBox: '0 0 660 224', role: 'img', 'aria-label': `Wartość brutto od ${formatDate(points[0].date)} do ${formatDate(points.at(-1).date)}` });
  for (let i = 0; i <= 3; i++) {
    const value = min + span * i / 3;
    svg.append(svgNode('line', { x1: 92, x2: 640, y1: y(value), y2: y(value), class: 'chart-grid' }));
    svg.append(svgNode('text', { x: 82, y: y(value) + 4, 'text-anchor': 'end' }, new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2, notation: value >= 1e7 ? 'compact' : 'standard' }).format(value)));
  }
  const line = values.map((value, i) => `${i ? 'L' : 'M'}${x(i).toFixed(2)},${y(value).toFixed(2)}`).join(' ');
  svg.append(svgNode('path', { d: `${line} L${x(values.length - 1)},186 L${x(0)},186 Z`, class: 'chart-area' }));
  svg.append(svgNode('path', { d: line, class: 'chart-line' }));
  const marker = svgNode('circle', { cx: x(values.length - 1), cy: y(values.at(-1)), r: 4, fill: 'var(--ui-chart-accent)' });
  svg.append(marker);
  svg.append(svgNode('text', { x: 92, y: 216 }, formatDate(points[0].date)));
  svg.append(svgNode('text', { x: 640, y: 216, 'text-anchor': 'end' }, formatDate(points.at(-1).date)));
  const caption = document.createElement('p');
  caption.className = 'chart-caption';
  const range = document.createElement('input');
  range.type = 'range';
  range.min = '0';
  range.max = String(points.length - 1);
  range.value = range.max;
  range.setAttribute('aria-label', 'Dzień na wykresie wartości');
  range.disabled = points.length === 1;
  const select = (index) => {
    const point = points[index];
    caption.textContent = `${formatDate(point.date)} · ${money(point.totalValue)} · odsetki ${money(point.totalAccruedInterest)}`;
    range.setAttribute('aria-valuetext', caption.textContent);
    marker.setAttribute('cx', x(index));
    marker.setAttribute('cy', y(values[index]));
  };
  range.addEventListener('input', () => select(Number(range.value)));
  svg.addEventListener('pointermove', (event) => {
    const bounds = svg.getBoundingClientRect();
    const fraction = ((event.clientX - bounds.left) / bounds.width * 660 - 92) / 548;
    const index = Math.max(0, Math.min(points.length - 1, Math.round(fraction * (points.length - 1))));
    range.value = String(index);
    select(index);
  });
  select(points.length - 1);
  container.append(svg, range, caption);
  container.hidden = false;
}
