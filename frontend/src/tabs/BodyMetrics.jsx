import { useState } from 'react';
import { api } from '../api.js';
import { daysBefore, fmt, numberToInput, plural, toNumber, todayLocal } from '../format.js';
import { useLoad } from '../useLoad.js';
import { toast } from '../components/Toast.jsx';
import WeightChart from '../components/WeightChart.jsx';

function emptyForm() {
  return { weighDate: todayLocal(), weightKg: '', notes: '' };
}

function sortWeighIns(list) {
  return [...list].sort((a, b) => b.weighDate.localeCompare(a.weighDate) || b.id - a.id);
}

export default function BodyMetrics({ authEpoch }) {
  const weighIns = useLoad(() => api('/api/weigh-ins'), authEpoch);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState(null);
  const [message, setMessage] = useState({ text: '', type: '' });
  const [saving, setSaving] = useState(false);

  const list = weighIns.data || [];
  const today = todayLocal();
  const chartStart = daysBefore(today, 29);
  const chartPoints = list
    .filter((w) => w.weighDate >= chartStart && w.weighDate <= today)
    .slice()
    .reverse()
    .map((w) => ({ date: w.weighDate, weightKg: w.weightKg }));

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  function startEdit(w) {
    setEditingId(w.id);
    setForm({ weighDate: w.weighDate, weightKg: numberToInput(w.weightKg), notes: w.notes });
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
    const body = { weighDate: form.weighDate, weightKg: toNumber(form.weightKg), notes: form.notes };
    try {
      const saved = editingId
        ? await api(`/api/weigh-ins/${editingId}`, { method: 'PUT', body })
        : await api('/api/weigh-ins', { method: 'POST', body });
      weighIns.setData((l) => sortWeighIns([saved, ...(l || []).filter((w) => w.id !== saved.id)]));
      setEditingId(null);
      setForm(emptyForm());
      setMessage({ text: 'Saved.', type: 'success' });
      toast(editingId ? 'Weigh-in updated' : 'Weigh-in saved');
    } catch (e) {
      setMessage({ text: e.message, type: 'error' });
    } finally {
      setSaving(false);
    }
  }

  async function remove(w) {
    if (!confirm(`Delete the weigh-in from ${w.weighDate}? This cannot be undone.`)) return;
    try {
      await api(`/api/weigh-ins/${w.id}`, { method: 'DELETE' });
      toast('Weigh-in deleted');
    } catch (e) {
      if (e.status !== 404) {
        toast(e.message, true);
        return;
      }
    }
    weighIns.setData((l) => (l || []).filter((x) => x.id !== w.id));
    if (editingId === w.id) cancelEdit();
  }

  return (
    <section className="page">
      <div className="section-intro">
        <div>
          <div className="eyebrow accent">THE LONG VIEW</div>
          <h2>Body metrics</h2>
          <p className="subtle">Track measurements as trends. Day-to-day changes are normal.</p>
        </div>
      </div>

      <div className="content-grid form-layout">
        <article className="panel form-panel">
          <div className="panel-heading">
            <div>
              <div className="eyebrow">{editingId ? 'EDIT ENTRY' : 'NEW ENTRY'}</div>
              <h3>{editingId ? `Editing ${form.weighDate}` : 'Log body weight'}</h3>
            </div>
            <span className="form-icon">⌁</span>
          </div>
          <form className="form-grid" onSubmit={submit}>
            <label className="field">
              Weight (kg)
              <input inputMode="decimal" value={form.weightKg} onChange={set('weightKg')} placeholder="e.g. 72.4" required />
            </label>
            <label className="field">
              Date
              <input type="date" value={form.weighDate} onChange={set('weighDate')} required />
            </label>
            <label className="field span-2">
              Notes (optional)
              <textarea
                value={form.notes}
                onChange={set('notes')}
                placeholder="e.g. BF: 17.5%, MM: 37% — morning, before breakfast"
                rows={2}
                maxLength={500}
              />
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
                  {editingId ? 'Save' : 'Save weigh-in'} <span>→</span>
                </button>
              </div>
            </div>
          </form>
          <div className="form-footnote">
            For a more comparable trend, weigh under similar conditions—often in the morning after using the bathroom.
            Body-fat or muscle readings go in the notes.
          </div>
        </article>
        <article className="panel trend-panel">
          <div className="eyebrow">RECENT TREND</div>
          <h3>Weight, last 30 days</h3>
          <WeightChart points={chartPoints} today={today} />
        </article>
      </div>

      <article className="panel table-panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">HISTORY</div>
            <h3>Weigh-ins</h3>
          </div>
          <span className="table-count">{plural(list.length, 'entry', 'entries')}</span>
        </div>
        {weighIns.error && <div className="form-message error history-message">{weighIns.error}</div>}
        <div className="table-wrap">
          <table className="stack">
            <thead>
              <tr>
                <th>Date</th>
                <th>Weight</th>
                <th>Notes</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.length === 0 ? (
                <tr>
                  <td colSpan={4} className="empty-cell">
                    {weighIns.loading ? 'Loading…' : 'No weigh-ins logged yet.'}
                  </td>
                </tr>
              ) : (
                list.map((w) => (
                  <tr key={w.id}>
                    <td data-label="Date">{w.weighDate}</td>
                    <td data-label="Weight">
                      <strong>{fmt(w.weightKg, 1)} kg</strong>
                    </td>
                    <td data-label="Notes" className="wrap-cell">{w.notes || '—'}</td>
                    <td className="row-actions">
                      <button type="button" className="delete-button edit-button" onClick={() => startEdit(w)}>
                        Edit
                      </button>
                      <button type="button" className="delete-button" onClick={() => remove(w)}>
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
