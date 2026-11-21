// Safe localStorage access. Storage can be unavailable (private mode, blocked site data) or full;
// none of that may crash the app.

/** Returns { ok: true, value } (value is null when the key is absent) or { ok: false, raw } on bad JSON. */
export function readJson(key) {
  let raw;
  try {
    raw = window.localStorage.getItem(key);
  } catch {
    return { ok: true, value: null };
  }
  if (raw === null) return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(raw) };
  } catch {
    return { ok: false, raw };
  }
}

export function writeJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function writeRaw(key, raw) {
  try {
    window.localStorage.setItem(key, raw);
    return true;
  } catch {
    return false;
  }
}

export function remove(key) {
  try {
    window.localStorage.removeItem(key);
  } catch {
    // ignore
  }
}
