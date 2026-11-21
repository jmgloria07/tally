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

/**
 * Moves values saved under old key names (the app was called FitLocal) to their new names, once.
 * An existing value under the new name always wins; the old key is only removed after a successful copy,
 * so an in-progress workout draft can't be lost if storage is full.
 */
export function migrateLegacyKeys(renames) {
  for (const [oldKey, newKey] of Object.entries(renames)) {
    try {
      const raw = window.localStorage.getItem(oldKey);
      if (raw === null) continue;
      if (window.localStorage.getItem(newKey) === null) window.localStorage.setItem(newKey, raw);
      window.localStorage.removeItem(oldKey);
    } catch {
      // Storage unavailable or full: leave the old key in place and try again next load.
    }
  }
}
