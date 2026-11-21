// Number and date helpers. All dates are the browser's local calendar date as YYYY-MM-DD.

export function todayLocal(date = new Date()) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** YYYY-MM-DD for `days` before the given ISO date. */
export function daysBefore(isoDate, days) {
  const d = new Date(`${isoDate}T12:00:00`);
  d.setDate(d.getDate() - days);
  return todayLocal(d);
}

export function fmt(n, digits = 0) {
  return Number(n || 0).toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: 0 });
}

export function round1(n) {
  return Math.round(Number(n || 0) * 10) / 10;
}

export function percent(value, target) {
  return target > 0 ? (Number(value || 0) / target) * 100 : 0;
}

export function dateChip(isoDate) {
  return new Date(`${isoDate}T12:00:00`)
    .toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })
    .toUpperCase();
}

/** Parses a form string to a number, or null when blank or not a number. */
export function toNumber(text) {
  if (text === null || text === undefined) return null;
  const trimmed = String(text).trim();
  if (trimmed === '') return null;
  const n = Number(trimmed.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

export function numberToInput(n) {
  return n === null || n === undefined ? '' : String(n);
}

export function plural(count, one, many) {
  return `${count} ${count === 1 ? one : many}`;
}
