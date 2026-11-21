import { useEffect, useState } from 'react';

// A single app-wide toast. toast() can be called from anywhere; <Toast /> renders it.
let show = () => {};

export function toast(message, error = false) {
  show({ message, error, id: Date.now() });
}

export default function Toast() {
  const [current, setCurrent] = useState(null);

  useEffect(() => {
    show = setCurrent;
    return () => {
      show = () => {};
    };
  }, []);

  useEffect(() => {
    if (!current) return undefined;
    const timer = setTimeout(() => setCurrent(null), 3200);
    return () => clearTimeout(timer);
  }, [current]);

  return (
    <div className={`toast ${current ? 'show' : ''} ${current?.error ? 'error' : ''}`} role="status" aria-live="polite">
      {current?.message}
    </div>
  );
}
