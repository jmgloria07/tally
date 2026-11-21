import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { numberToInput, toNumber } from '../format.js';
import { useLoad } from '../useLoad.js';
import { toast } from '../components/Toast.jsx';

const NUMBER_FIELDS = [
  ['caloriesTarget', 'Calories (kcal)'],
  ['proteinTarget', 'Protein (g)'],
  ['carbsTarget', 'Carbs (g)'],
  ['fatTarget', 'Fat (g)'],
];

function toForm(s) {
  return {
    caloriesTarget: numberToInput(s.caloriesTarget),
    proteinTarget: numberToInput(s.proteinTarget),
    carbsTarget: numberToInput(s.carbsTarget),
    fatTarget: numberToInput(s.fatTarget),
    goal: s.goal,
    dietaryPreferences: s.dietaryPreferences,
    injuriesLimitations: s.injuriesLimitations,
    coachingStyle: s.coachingStyle,
  };
}

export default function Goals({ authEpoch, onLogout }) {
  const settings = useLoad(() => api('/api/settings'), authEpoch);
  const [form, setForm] = useState(null);
  const [message, setMessage] = useState({ text: '', type: '' });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (settings.data) setForm(toForm(settings.data));
  }, [settings.data]);

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setMessage({ text: 'Saving…', type: '' });
    const body = { ...form };
    NUMBER_FIELDS.forEach(([key]) => {
      body[key] = toNumber(form[key]);
    });
    try {
      const saved = await api('/api/settings', { method: 'PUT', body });
      settings.setData(saved);
      setMessage({ text: 'Preferences saved. The coach will use them from your next question.', type: 'success' });
      toast('Preferences saved');
    } catch (e) {
      setMessage({ text: e.message, type: 'error' });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="page">
      <div className="section-intro">
        <div>
          <div className="eyebrow accent">MAKE IT YOURS</div>
          <h2>Goals & preferences</h2>
          <p className="subtle">
            These are your own tracking targets, not medical prescriptions. Everything here is what the coach sees
            about you.
          </p>
        </div>
      </div>

      {!form ? (
        <div className="empty-state">{settings.error || 'Loading…'}</div>
      ) : (
        <form className="settings-layout" onSubmit={submit}>
          <article className="panel settings-panel">
            <div className="panel-heading">
              <div>
                <div className="eyebrow">YOUR NORTH STAR</div>
                <h3>Goal & daily targets</h3>
              </div>
            </div>
            <label className="field">
              Main goal
              <textarea value={form.goal} onChange={set('goal')} rows={2} maxLength={2000} placeholder="e.g. Build strength and improve body composition" />
            </label>
            <div className="form-grid settings-numbers">
              {NUMBER_FIELDS.map(([key, label]) => (
                <label className="field" key={key}>
                  {label}
                  <input inputMode="decimal" value={form[key]} onChange={set(key)} required />
                </label>
              ))}
            </div>
            <div className="form-footnote">Change targets when you have a reason; don't treat one day of data as a verdict.</div>
          </article>

          <article className="panel settings-panel">
            <div className="panel-heading">
              <div>
                <div className="eyebrow">RELEVANT CONTEXT</div>
                <h3>Preferences & limitations</h3>
              </div>
            </div>
            <label className="field">
              Dietary preferences / food restrictions
              <textarea
                value={form.dietaryPreferences}
                onChange={set('dietaryPreferences')}
                rows={3}
                maxLength={1000}
                placeholder="Foods you avoid, allergies, preferred cuisines…"
              />
            </label>
            <label className="field">
              Injuries, limitations, or movements to avoid
              <textarea
                value={form.injuriesLimitations}
                onChange={set('injuriesLimitations')}
                rows={3}
                maxLength={1000}
                placeholder="Optional. Include only what you want your coach to consider."
              />
            </label>
            <label className="field">
              Coaching style
              <textarea value={form.coachingStyle} onChange={set('coachingStyle')} rows={2} maxLength={1000} placeholder="e.g. Practical, direct, encouraging" />
            </label>
            <div className="form-actions">
              <span className={`form-message ${message.type}`}>{message.text}</span>
              <button className="button button-primary" type="submit" disabled={saving}>
                Save preferences →
              </button>
            </div>
          </article>
        </form>
      )}

      <article className="panel data-panel">
        <div className="eyebrow">LOCAL DATA</div>
        <h3>Your records stay on your PC</h3>
        <p>
          Tally stores logs in a SQLite database in the app's <code>data</code> folder and does not connect to a
          cloud service. When you ask the coach a question, the last 4 weeks of records and these preferences are
          sent to the LM Studio server running on the same computer.
        </p>
        <button type="button" className="button button-outline" onClick={onLogout}>
          Sign out of this device
        </button>
      </article>
    </section>
  );
}
