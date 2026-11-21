import { useState } from 'react';
import { api } from '../api.js';
import { fmt, numberToInput, plural, toNumber, todayLocal } from '../format.js';
import { useLoad } from '../useLoad.js';
import { toast } from '../components/Toast.jsx';

const MEAL_IDEA_PROMPT =
  "Suggest a meal that fits what's left of today's targets and my dietary preferences. Ask about missing constraints rather than guessing.";

function emptyForm() {
  return { mealDate: todayLocal(), food: '', calories: '', proteinG: '', carbsG: '', fatG: '', notes: '' };
}

function sortMeals(list) {
  return [...list].sort((a, b) => b.mealDate.localeCompare(a.mealDate) || b.id - a.id);
}

export default function Nutrition({ authEpoch, askCoach }) {
  // Both requests start on mount, in parallel.
  const meals = useLoad(() => api('/api/meals'), authEpoch);
  const settings = useLoad(() => api('/api/settings'), authEpoch);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState(null);
  const [message, setMessage] = useState({ text: '', type: '' });
  const [saving, setSaving] = useState(false);

  const list = meals.data || [];
  const today = todayLocal();
  const todays = list.filter((m) => m.mealDate === today);
  const total = (key) => todays.reduce((n, m) => n + Number(m[key] || 0), 0);
  const s = settings.data;

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  function startEdit(meal) {
    setEditingId(meal.id);
    setForm({
      mealDate: meal.mealDate,
      food: meal.food,
      calories: numberToInput(meal.calories),
      proteinG: numberToInput(meal.proteinG),
      carbsG: numberToInput(meal.carbsG),
      fatG: numberToInput(meal.fatG),
      notes: meal.notes,
    });
    setMessage({ text: '', type: '' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function cancelEdit() {
    setEditingId(null);
    setForm(emptyForm());
    setMessage({ text: '', type: '' });
  }

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setMessage({ text: 'Saving…', type: '' });
    const body = {
      mealDate: form.mealDate,
      food: form.food,
      calories: toNumber(form.calories),
      proteinG: toNumber(form.proteinG),
      carbsG: toNumber(form.carbsG),
      fatG: toNumber(form.fatG),
      notes: form.notes,
    };
    try {
      const saved = editingId
        ? await api(`/api/meals/${editingId}`, { method: 'PUT', body })
        : await api('/api/meals', { method: 'POST', body });
      meals.setData((l) => sortMeals([saved, ...(l || []).filter((m) => m.id !== saved.id)]));
      setEditingId(null);
      setForm(emptyForm());
      setMessage({ text: 'Saved.', type: 'success' });
      toast(editingId ? 'Meal updated' : 'Meal saved');
    } catch (e) {
      setMessage({ text: e.message, type: 'error' });
    } finally {
      setSaving(false);
    }
  }

  async function remove(meal) {
    if (!confirm(`Delete "${meal.food}"? This cannot be undone.`)) return;
    try {
      await api(`/api/meals/${meal.id}`, { method: 'DELETE' });
      toast('Meal deleted');
    } catch (e) {
      if (e.status !== 404) {
        toast(e.message, true);
        return;
      }
    }
    meals.setData((l) => (l || []).filter((m) => m.id !== meal.id));
    if (editingId === meal.id) cancelEdit();
  }

  return (
    <section className="page">
      <div className="section-intro">
        <div>
          <div className="eyebrow accent">FUEL YOUR DAY</div>
          <h2>Nutrition</h2>
          <p className="subtle">Log meals and use estimates when exact labels aren't available.</p>
        </div>
      </div>

      <div className="stats-grid nutrition-stats">
        <StatCard label="CALORIES" value={fmt(total('calories'))} unit="kcal today" target={s && `of ${fmt(s.caloriesTarget)} kcal`} />
        <StatCard label="PROTEIN" value={fmt(total('proteinG'), 1)} unit="g today" target={s && `of ${fmt(s.proteinTarget)} g`} />
        <StatCard label="CARBS" value={fmt(total('carbsG'), 1)} unit="g today" target={s && `of ${fmt(s.carbsTarget)} g`} />
        <StatCard label="FAT" value={fmt(total('fatG'), 1)} unit="g today" target={s && `of ${fmt(s.fatTarget)} g`} />
      </div>

      <div className="content-grid form-layout">
        <article className="panel form-panel">
          <div className="panel-heading">
            <div>
              <div className="eyebrow">{editingId ? 'EDIT ENTRY' : 'NEW ENTRY'}</div>
              <h3>{editingId ? `Editing ${form.mealDate}` : 'Log food'}</h3>
            </div>
            <span className="form-icon">◉</span>
          </div>
          <form className="form-grid" onSubmit={submit}>
            <label className="field span-2">
              Food or meal
              <input value={form.food} onChange={set('food')} placeholder="e.g. Rice, grilled fish, vegetables" maxLength={180} required />
            </label>
            <label className="field">
              Date
              <input type="date" value={form.mealDate} onChange={set('mealDate')} required />
            </label>
            <label className="field">
              Calories (kcal)
              <input inputMode="decimal" value={form.calories} onChange={set('calories')} placeholder="0" required />
            </label>
            <label className="field">
              Protein (g)
              <input inputMode="decimal" value={form.proteinG} onChange={set('proteinG')} placeholder="0" />
            </label>
            <label className="field">
              Carbs (g)
              <input inputMode="decimal" value={form.carbsG} onChange={set('carbsG')} placeholder="0" />
            </label>
            <label className="field">
              Fat (g)
              <input inputMode="decimal" value={form.fatG} onChange={set('fatG')} placeholder="0" />
            </label>
            <label className="field span-2">
              Notes (optional)
              <textarea value={form.notes} onChange={set('notes')} placeholder="Portion size, brand, meal type…" rows={2} maxLength={500} />
            </label>
            <div className="span-2 form-actions">
              <span className={`form-message ${message.type}`}>{message.text}</span>
              <div className="button-row">
                {editingId && (
                  <button type="button" className="button button-outline" onClick={cancelEdit}>
                    Cancel
                  </button>
                )}
                <button className="button button-primary" type="submit" disabled={saving}>
                  {editingId ? 'Save' : 'Save meal'} <span>→</span>
                </button>
              </div>
            </div>
          </form>
          <div className="form-footnote">
            Nutrition values are only as accurate as the information entered. The app does not look up food databases.
          </div>
        </article>
        <article className="panel tip-panel">
          <div className="tip-number">02 / NUTRITION</div>
          <h3>A useful estimate beats an empty log.</h3>
          <p>
            Use the package label when you have it. For home-cooked meals, enter a reasonable estimate and keep
            portions consistent.
          </p>
          <div className="tip-divider" />
          <button type="button" className="button button-outline full-width" onClick={() => askCoach(MEAL_IDEA_PROMPT)}>
            Ask for a meal idea
          </button>
        </article>
      </div>

      <article className="panel table-panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">HISTORY</div>
            <h3>Recent meals</h3>
          </div>
          <span className="table-count">{plural(list.length, 'entry', 'entries')}</span>
        </div>
        {meals.error && <div className="form-message error history-message">{meals.error}</div>}
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Date</th>
                <th>Food</th>
                <th>Calories</th>
                <th>Protein</th>
                <th>Carbs</th>
                <th>Fat</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.length === 0 ? (
                <tr>
                  <td colSpan={7} className="empty-cell">
                    {meals.loading ? 'Loading…' : 'No meals logged yet.'}
                  </td>
                </tr>
              ) : (
                list.map((m) => (
                  <tr key={m.id}>
                    <td data-label="Date">{m.mealDate}</td>
                    <td data-label="Food" title={m.notes}>
                      <strong>{m.food}</strong>
                    </td>
                    <td data-label="Calories">{fmt(m.calories)}</td>
                    <td data-label="Protein">{fmt(m.proteinG, 1)} g</td>
                    <td data-label="Carbs">{fmt(m.carbsG, 1)} g</td>
                    <td data-label="Fat">{fmt(m.fatG, 1)} g</td>
                    <td className="row-actions">
                      <button type="button" className="delete-button edit-button" onClick={() => startEdit(m)}>
                        Edit
                      </button>
                      <button type="button" className="delete-button" onClick={() => remove(m)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </article>
    </section>
  );
}

function StatCard({ label, value, unit, target }) {
  return (
    <article className="stat-card compact">
      <div className="stat-top">
        <span>{label}</span>
      </div>
      <div className="stat-value">
        <span>{value}</span>
        <small>{unit}</small>
      </div>
      <div className="stat-foot">
        <span>{target || ' '}</span>
      </div>
    </article>
  );
}
