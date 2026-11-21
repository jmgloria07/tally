import { useState } from 'react';
import { api } from '../api.js';
import BrandMark from './BrandMark.jsx';

// Drawn on top of the mounted app so in-progress form state survives a 401.
// The failed action is not retried automatically; the user taps it again.
export default function LoginOverlay({ onSignedIn }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api('/api/auth/login', { method: 'POST', body: { username: username.trim(), password } });
      setPassword('');
      onSignedIn();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-overlay" role="dialog" aria-modal="true" aria-labelledby="login-title">
      <form className="panel login-card" onSubmit={submit}>
        <div className="brand">
          <BrandMark />
          <div>
            <div className="brand-name">tally</div>
            <div className="brand-caption">YOUR PRIVATE COACH</div>
          </div>
        </div>
        <h2 id="login-title">Sign in</h2>
        <p className="subtle">Anything you were working on is still here.</p>
        <label className="field">
          Username
          <input
            name="username"
            autoComplete="username"
            autoCapitalize="none"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            autoFocus
          />
        </label>
        <label className="field">
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && <div className="form-message error">{error}</div>}
        <button className="button button-primary full-width" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
