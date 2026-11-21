import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { renderMarkdown } from '../markdown.js';
import { readJson, writeJson } from '../storage.js';

const CHAT_KEY = 'fitlocal.chat.v1';
const MAX_HISTORY = 12;

// Exactly three chips (spec §8.4). Chips fill the composer so the prompt can be edited before sending.
const CHIPS = [
  {
    label: 'Weekly review',
    prompt:
      "Write my weekly review. Start from the 'at a glance' summary and use its numbers as given; use the daily lines for detail and the earlier blocks for comparison. Include any training logged today. Sections: what the logs show, wins, gaps in tracking, and one to three practical actions for next week. Do not infer a weight trend from fewer than two weigh-ins. Do not change my calorie or macro targets; if you think they should change, explain why and ask me.",
  },
  {
    label: 'Plan a workout',
    prompt:
      'Help me plan my next workout based on my recent sessions. Base loads on what I actually lifted. Ask for any missing details before making assumptions.',
  },
  {
    label: 'Meal idea',
    prompt:
      "Suggest a meal that fits what's left of today's targets and my dietary preferences. Ask about missing constraints rather than guessing.",
  },
];

function loadHistory() {
  const { ok, value } = readJson(CHAT_KEY);
  if (!ok || !Array.isArray(value)) return [];
  return value
    .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')
    .slice(-MAX_HISTORY);
}

export default function Coach({ authEpoch, navigate, pendingPrompt, consumePrompt }) {
  const [history, setHistory] = useState(loadHistory);
  const [pending, setPending] = useState(null); // { question, error }
  const [input, setInput] = useState('');
  const [health, setHealth] = useState(null);
  const messagesRef = useRef(null);
  const inputRef = useRef(null);

  const refreshHealth = useCallback(() => {
    api('/api/health')
      .then(setHealth)
      .catch(() => setHealth({ lmStudio: 'offline', model: null }));
  }, []);

  useEffect(refreshHealth, [refreshHealth, authEpoch]);

  useEffect(() => {
    if (pendingPrompt) {
      setInput(pendingPrompt);
      consumePrompt();
      inputRef.current?.focus();
    }
    // Consume once, on mount.
  }, []);

  useEffect(() => {
    const el = messagesRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [history, pending]);

  const busy = pending !== null && !pending.error;

  // "What the coach sees": fetched fresh each time it is opened, since the data changes as you log.
  const [context, setContext] = useState({ open: false, loading: false, data: null, error: '' });

  async function loadContext() {
    setContext((c) => ({ ...c, open: true, loading: true, error: '' }));
    try {
      const data = await api('/api/coach/context');
      setContext({ open: true, loading: false, data, error: '' });
    } catch (e) {
      setContext((c) => ({ ...c, loading: false, error: e.message }));
    }
  }

  function toggleContext() {
    if (context.open) setContext((c) => ({ ...c, open: false }));
    else loadContext();
  }

  async function send() {
    const question = input.trim();
    if (!question || busy) return;
    setInput('');
    setPending({ question, error: '' });
    try {
      const result = await api('/api/coach', { method: 'POST', body: { message: question, history } });
      const next = [...history, { role: 'user', content: question }, { role: 'assistant', content: result.answer }].slice(
        -MAX_HISTORY,
      );
      setHistory(next);
      writeJson(CHAT_KEY, next);
      setPending(null);
    } catch (e) {
      // Shown in the bubble; a failed turn is not added to history.
      setPending({ question, error: e.message });
      refreshHealth();
    }
  }

  function clearChat() {
    setHistory([]);
    setPending(null);
    writeJson(CHAT_KEY, []);
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  }

  // LM Studio can be running with no model loaded; the coach only works when a model is loaded (D6).
  const online = health?.lmStudio === 'online';
  const ready = online && Boolean(health?.model);
  const status = !health
    ? 'Checking local model…'
    : ready
      ? `Connected · ${health.model}`
      : online
        ? 'LM Studio running · no model loaded'
        : 'LM Studio offline';
  const subtitle = !health || ready
    ? 'A practical partner for training and nutrition.'
    : online
      ? 'Your logs are fine; load a model in LM Studio to get answers.'
      : 'Your logs are fine; answers need the LM Studio server running.';

  return (
    <section className="page">
      <div className="section-intro coach-intro">
        <div>
          <div className="eyebrow accent">RUNNING LOCALLY</div>
          <h2>Your AI coach</h2>
          <p className="subtle">Ask for practical guidance grounded in your recent logs and saved preferences.</p>
        </div>
        <div className="model-badge">
          <span className={`status-dot ${health ? (ready ? 'online' : 'offline') : ''}`} />
          <span>{status}</span>
        </div>
      </div>

      <div className="coach-layout">
        <article className="panel chat-panel">
          <div className="chat-header">
            <div className="coach-orb large">✳</div>
            <div>
              <h3>FitLocal coach</h3>
              <p>{subtitle}</p>
            </div>
            <button type="button" className="text-button" onClick={clearChat}>
              Clear chat
            </button>
          </div>

          <div className="chat-messages" ref={messagesRef}>
            {history.length === 0 && !pending && (
              <Message role="assistant">
                Hi. I can use your logged workouts, meals, weight entries, and saved goals from the last 4 weeks.
                Ask me to review your week, plan your next session, or work through a nutrition question.
              </Message>
            )}
            {history.map((m, i) => (
              <Message key={i} role={m.role} markdown={m.content} />
            ))}
            {pending && (
              <>
                <Message role="user" markdown={pending.question} />
                {pending.error ? (
                  <Message role="assistant" error>
                    {pending.error}
                  </Message>
                ) : (
                  <Message role="assistant" thinking>
                    Thinking…
                  </Message>
                )}
              </>
            )}
          </div>

          <div className="chat-suggestions">
            {CHIPS.map((chip) => (
              <button
                key={chip.label}
                type="button"
                className="prompt-chip"
                onClick={() => {
                  setInput(chip.prompt);
                  inputRef.current?.focus();
                }}
              >
                {chip.label}
              </button>
            ))}
          </div>

          <form
            className="chat-compose"
            onSubmit={(e) => {
              e.preventDefault();
              send();
            }}
          >
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Ask your coach…"
              rows={2}
              maxLength={3000}
              aria-label="Message"
            />
            <button className="send-button" type="submit" aria-label="Send message" disabled={busy || !input.trim()}>
              ↑
            </button>
            <div className="chat-compose-note">Your last 4 weeks of logs and your preferences are included with each question.</div>
          </form>
        </article>

        <aside className="coach-aside">
          <article className="panel coach-note">
            <div className="eyebrow">COACHING CONTEXT</div>
            <h3>Personalize your advice</h3>
            <p>Your goal, dietary preferences, and limitations help keep suggestions relevant.</p>
            <button type="button" className="button button-outline full-width" onClick={() => navigate('goals')}>
              Edit preferences
            </button>
          </article>
          <article className="panel coach-note">
            <div className="eyebrow">TRANSPARENCY</div>
            <h3>What the coach sees</h3>
            <p>The exact data and instructions sent with your next question, built from your logs right now.</p>
            <button type="button" className="button button-outline full-width" onClick={toggleContext}>
              {context.open ? 'Hide context' : 'View context'}
            </button>
          </article>
        </aside>
      </div>
      {context.open && <ContextView context={context} onRefresh={loadContext} />}
      <div className="disclaimer">
        AI responses can be inaccurate. This coach does not diagnose medical issues and should not override
        professional advice.
      </div>
    </section>
  );
}

function ContextView({ context, onRefresh }) {
  return (
    <article className="panel context-view">
      <div className="panel-heading">
        <div>
          <div className="eyebrow">WHAT THE COACH SEES</div>
          <h3>Tracker data sent with your next question</h3>
        </div>
        <button type="button" className="text-button" onClick={onRefresh} disabled={context.loading}>
          Refresh ↻
        </button>
      </div>
      {context.loading && <div className="empty-state">Loading…</div>}
      {context.error && <div className="form-message error">{context.error}</div>}
      {context.data && (
        <>
          <pre className="context-text">{context.data.trackerBlock}</pre>
          <details className="context-details">
            <summary>Instructions and profile (system prompt)</summary>
            <pre className="context-text">{context.data.systemPrompt}</pre>
          </details>
        </>
      )}
    </article>
  );
}

function Message({ role, markdown, thinking = false, error = false, children }) {
  const isUser = role === 'user';
  return (
    <div className={`message ${isUser ? 'user-message' : 'assistant-message'}`}>
      <div className="message-avatar">{isUser ? 'YOU' : 'F.'}</div>
      <div className="message-bubble">
        <div className="message-author">{isUser ? 'YOU' : 'FITLOCAL COACH'}</div>
        {markdown !== undefined ? (
          // Sanitized by DOMPurify inside renderMarkdown.
          <div className="message-content" dangerouslySetInnerHTML={{ __html: renderMarkdown(markdown) }} />
        ) : (
          <div className={`message-content ${thinking ? 'typing-indicator' : ''} ${error ? 'message-error' : ''}`}>
            {children}
          </div>
        )}
      </div>
    </div>
  );
}
