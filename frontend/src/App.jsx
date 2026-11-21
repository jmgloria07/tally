import { useCallback, useEffect, useState } from 'react';
import { api, onUnauthorized } from './api.js';
import { dateChip, todayLocal } from './format.js';
import LoginOverlay from './components/LoginOverlay.jsx';
import Toast, { toast } from './components/Toast.jsx';
import Overview from './tabs/Overview.jsx';
import Workout from './tabs/Workout.jsx';
import Nutrition from './tabs/Nutrition.jsx';
import BodyMetrics from './tabs/BodyMetrics.jsx';
import Coach from './tabs/Coach.jsx';
import Goals from './tabs/Goals.jsx';

// Tab ids live in the URL hash so a refresh keeps the tab. Short labels are for the phone bottom bar.
export const TABS = [
  { id: 'overview', label: 'Overview', short: 'Overview', icon: '◫', eyebrow: 'YOUR FITNESS, AT A GLANCE' },
  { id: 'workout', label: 'Workout log', short: 'Workout', icon: '↗', eyebrow: 'YOUR FITNESS WORKSPACE' },
  { id: 'nutrition', label: 'Nutrition', short: 'Food', icon: '◉', eyebrow: 'YOUR FITNESS WORKSPACE' },
  { id: 'body', label: 'Body metrics', short: 'Body', icon: '⌁', eyebrow: 'YOUR FITNESS WORKSPACE' },
  { id: 'coach', label: 'AI coach', short: 'Coach', icon: '✳', eyebrow: 'PRIVATE, LOCAL-GENERATED GUIDANCE' },
  { id: 'goals', label: 'Goals & preferences', short: 'Goals', icon: '⚙', eyebrow: 'YOUR FITNESS WORKSPACE' },
];

function tabFromHash() {
  const id = window.location.hash.replace(/^#/, '');
  return TABS.some((t) => t.id === id) ? id : 'overview';
}

export default function App() {
  const [tab, setTab] = useState(tabFromHash);
  const [needsLogin, setNeedsLogin] = useState(false);
  const [authEpoch, setAuthEpoch] = useState(0);
  const [pendingPrompt, setPendingPrompt] = useState('');

  useEffect(() => {
    const onHashChange = () => {
      setTab(tabFromHash());
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    onUnauthorized(() => setNeedsLogin(true));
  }, []);

  const navigate = useCallback((id) => {
    window.location.hash = id;
  }, []);

  const askCoach = useCallback(
    (prompt) => {
      setPendingPrompt(prompt);
      navigate('coach');
    },
    [navigate],
  );

  const signedIn = useCallback(() => {
    setNeedsLogin(false);
    setAuthEpoch((n) => n + 1);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch {
      // Even if the call fails, show the sign-in screen; the cookie expires on its own.
    }
    setNeedsLogin(true);
    toast('Signed out');
  }, []);

  const current = TABS.find((t) => t.id === tab);
  const props = { authEpoch, navigate, askCoach };

  return (
    <>
      <div className="app-shell">
        <aside className="sidebar">
          <div className="brand">
            <div className="brand-mark">F<span>.</span></div>
            <div>
              <div className="brand-name">fitlocal</div>
              <div className="brand-caption">YOUR PRIVATE COACH</div>
            </div>
          </div>
          <div className="side-label">WORKSPACE</div>
          <nav className="nav" aria-label="Main navigation">
            {TABS.filter((t) => t.id !== 'goals').map((t) => (
              <NavButton key={t.id} item={t} active={tab === t.id} onClick={() => navigate(t.id)} />
            ))}
          </nav>
          <div className="sidebar-bottom">
            <div className="privacy-card">
              <div className="privacy-icon">◈</div>
              <div className="privacy-title">Your data stays here</div>
              <p>Logs are stored on your PC. The coach runs through your local LM Studio server.</p>
            </div>
            <NavButton item={TABS[5]} active={tab === 'goals'} onClick={() => navigate('goals')} className="settings-link" />
            <div className="sidebar-footer">
              <span>FitLocal · local-first</span>
              <button type="button" className="text-button sidebar-logout" onClick={logout}>
                Sign out
              </button>
            </div>
          </div>
        </aside>

        <main className="main">
          <header className="topbar">
            <div className="topbar-left">
              <div className="eyebrow">{current.eyebrow}</div>
              <h1>{current.label.charAt(0).toUpperCase() + current.label.slice(1)}</h1>
            </div>
            <div className="topbar-right">
              <div className="date-chip">{dateChip(todayLocal())}</div>
            </div>
          </header>

          {tab === 'overview' && <Overview {...props} />}
          {tab === 'workout' && <Workout {...props} />}
          {tab === 'nutrition' && <Nutrition {...props} />}
          {tab === 'body' && <BodyMetrics {...props} />}
          {tab === 'coach' && (
            <Coach {...props} pendingPrompt={pendingPrompt} consumePrompt={() => setPendingPrompt('')} />
          )}
          {tab === 'goals' && <Goals {...props} onLogout={logout} />}

          <footer className="app-footer">
            <span>
              FITLOCAL <span className="footer-dot">•</span> PERSONAL FITNESS COACH
            </span>
            <span>LOCAL-FIRST · v0.1</span>
          </footer>
        </main>
      </div>

      <nav className="bottom-nav" aria-label="Main navigation">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={tab === t.id ? 'active' : ''}
            aria-current={tab === t.id ? 'page' : undefined}
            onClick={() => navigate(t.id)}
          >
            <span className="bottom-nav-icon" aria-hidden="true">{t.icon}</span>
            <span>{t.short}</span>
          </button>
        ))}
      </nav>

      {needsLogin && <LoginOverlay onSignedIn={signedIn} />}
      <Toast />
    </>
  );
}

function NavButton({ item, active, onClick, className = '' }) {
  return (
    <button type="button" className={`nav-item ${className} ${active ? 'active' : ''}`} onClick={onClick}>
      <span className="nav-icon">{item.icon}</span>
      {item.label}
      {item.id === 'coach' && <span className="nav-pill">LOCAL</span>}
    </button>
  );
}
