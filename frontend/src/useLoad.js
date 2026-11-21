import { useEffect, useState } from 'react';
import { UnauthorizedError } from './api.js';

/**
 * Runs `loader` once on mount and again whenever `authEpoch` changes (after a fresh sign-in).
 * A 401 is handled by the login overlay, so it is not shown as an error here.
 */
export function useLoad(loader, authEpoch) {
  const [state, setState] = useState({ data: null, error: '', loading: true });

  useEffect(() => {
    let cancelled = false;
    setState((s) => ({ ...s, loading: true, error: '' }));
    loader()
      .then((data) => {
        if (!cancelled) setState({ data, error: '', loading: false });
      })
      .catch((e) => {
        if (cancelled) return;
        setState((s) => ({ ...s, loading: false, error: e instanceof UnauthorizedError ? '' : e.message }));
      });
    return () => {
      cancelled = true;
    };
    // Reload only when the sign-in epoch changes; `loader` is a fresh closure on every render.
  }, [authEpoch]);

  const setData = (updater) =>
    setState((s) => ({ ...s, data: typeof updater === 'function' ? updater(s.data) : updater }));

  return { ...state, setData };
}
