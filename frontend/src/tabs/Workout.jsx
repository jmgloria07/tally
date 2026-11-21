import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { daysBefore, fmt, plural, todayLocal } from '../format.js';
import { useLoad } from '../useLoad.js';
import { toast } from '../components/Toast.jsx';
import {
  asNewDraft,
  draftFromWorkout,
  loadDraft,
  newDraft,
  newExercise,
  newSet,
  persistDraft,
  sortWorkouts,
  touch,
  validateDraft,
} from '../workoutDraft.js';

const IDLE = { saving: false, error: '', missing: false };

export default function Workout({ authEpoch, askCoach }) {
  const history = useLoad(() => api('/api/workouts'), authEpoch);
  const [initial] = useState(loadDraft);
  const [draft, setDraft] = useState(initial.draft);
  const [status, setStatus] = useState(IDLE);
  const [fieldErrors, setFieldErrors] = useState({});
  const [focusKey, setFocusKey] = useState(null);

  useEffect(() => {
    if (initial.corrupt) toast('Your saved workout draft could not be read. It was set aside, not deleted.', true);
  }, [initial.corrupt]);

  // The draft is written on every change, so it survives refresh and closing the browser.
  useEffect(() => {
    persistDraft(draft);
  }, [draft]);

  const update = (fn) => setDraft((d) => touch(fn(d)));
  const updateExercise = (key, fn) =>
    update((d) => ({ ...d, exercises: d.exercises.map((e) => (e.key === key ? fn(e) : e)) }));

  function start() {
    const d = newDraft(todayLocal());
    setDraft(d);
    setFocusKey(d.exercises[0].key);
    setStatus(IDLE);
    setFieldErrors({});
  }

  function edit(workout) {
    setDraft(draftFromWorkout(workout));
    setStatus(IDLE);
    setFieldErrors({});
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function discard() {
    if (!confirm('Discard this workout draft? This cannot be undone.')) return;
    setDraft(null);
    setStatus(IDLE);
    setFieldErrors({});
  }

  async function submit(current) {
    const { errors, payload } = validateDraft(current);
    setFieldErrors(errors);
    if (!payload) {
      setStatus({ ...IDLE, error: 'Fix the highlighted fields, then try again.' });
      return;
    }
    setStatus({ ...IDLE, saving: true });
    try {
      const saved =
        current.mode === 'new'
          ? await api('/api/workouts', { method: 'POST', body: { ...payload, submissionId: current.submissionId } })
          : await api(`/api/workouts/${current.workoutId}`, { method: 'PUT', body: payload });
      history.setData((list) => sortWorkouts([saved, ...(list || []).filter((w) => w.id !== saved.id)]));
      setDraft(null); // only after the server confirmed
      setStatus(IDLE);
      toast(current.mode === 'new' ? 'Workout saved' : 'Workout updated');
    } catch (e) {
      // Offline, server down, 401: the draft stays exactly as it is.
      if (e.status === 404 && current.mode === 'edit') {
        setStatus({ saving: false, error: 'This workout was deleted on another device.', missing: true });
      } else {
        setStatus({ ...IDLE, error: e.message });
      }
    }
  }

  function saveAsNew() {
    const d = asNewDraft(draft);
    setDraft(d);
    submit(d);
  }

  async function remove(workout) {
    if (!confirm(`Delete the workout from ${workout.workoutDate}? This cannot be undone.`)) return;
    try {
      await api(`/api/workouts/${workout.id}`, { method: 'DELETE' });
      toast('Workout deleted');
    } catch (e) {
      if (e.status !== 404) {
        toast(e.message, true);
        return;
      }
      toast('That workout was already deleted');
    }
    history.setData((list) => (list || []).filter((w) => w.id !== workout.id));
  }

  const workouts = history.data || [];
  const today = todayLocal();
  const weekStart = daysBefore(today, 6);
  const trainingDays = new Set(
    workouts.filter((w) => w.workoutDate >= weekStart && w.workoutDate <= today).map((w) => w.workoutDate),
  ).size;

  return (
    <section className="page">
      <div className="section-intro">
        <div>
          <div className="eyebrow accent">SHOW UP, LOG IT</div>
          <h2>Workout log</h2>
          <p className="subtle">Build the session as you train. It's saved on this device until you finish.</p>
        </div>
      </div>

      <div className="content-grid form-layout">
        {draft ? (
          <Editor
            draft={draft}
            status={status}
            fieldErrors={fieldErrors}
            focusKey={focusKey}
            update={update}
            updateExercise={updateExercise}
            setFocusKey={setFocusKey}
            onSubmit={() => submit(draft)}
            onDiscard={discard}
            onSaveAsNew={saveAsNew}
          />
        ) : (
          <article className="panel form-panel">
            <div className="panel-heading">
              <div>
                <div className="eyebrow">NEW SESSION</div>
                <h3>No workout in progress</h3>
              </div>
              <span className="form-icon">↗</span>
            </div>
            <p className="subtle">
              Start a session, add exercises, and log each set's reps and weight as you go. Everything is kept on
              this device until you tap Finish workout, even if you close the browser.
            </p>
            <div className="form-actions start-actions">
              <span />
              <button type="button" className="button button-primary" onClick={start}>
                Start workout <span>→</span>
              </button>
            </div>
          </article>
        )}

        <article className="panel tip-panel">
          <div className="tip-number">01 / TRAINING</div>
          <h3>Track the trend, not one session.</h3>
          <p>
            Compare the same exercise over time. Use controlled reps and consistent technique before increasing
            weight.
          </p>
          <div className="tip-divider" />
          <div className="tip-stat">
            <span>RECENT TRAINING DAYS</span>
            <b>
              {trainingDays} <small>of last 7</small>
            </b>
          </div>
          <button
            type="button"
            className="button button-outline full-width"
            onClick={() =>
              askCoach(
                'Help me plan my next workout based on my recent sessions. Base loads on what I actually lifted. Ask for any missing details before making assumptions.',
              )
            }
          >
            Review with AI coach
          </button>
        </article>
      </div>

      <article className="panel table-panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">HISTORY</div>
            <h3>Recent sessions</h3>
          </div>
          <span className="table-count">{plural(workouts.length, 'session', 'sessions')}</span>
        </div>
        {history.error && <div className="form-message error history-message">{history.error}</div>}
        {draft && workouts.length > 0 && (
          <div className="form-message history-message">Finish or discard your current workout first to edit another.</div>
        )}
        <div className="history-list">
          {workouts.length === 0 && !history.loading ? (
            <div className="empty-cell">No workouts logged yet.</div>
          ) : (
            workouts.map((w) => (
              <HistoryRow
                key={w.id}
                workout={w}
                editDisabled={draft !== null}
                deleteDisabled={draft?.mode === 'edit' && draft.workoutId === w.id}
                onEdit={() => edit(w)}
                onDelete={() => remove(w)}
              />
            ))
          )}
        </div>
      </article>
    </section>
  );
}

function Editor({ draft, status, fieldErrors, focusKey, update, updateExercise, setFocusKey, onSubmit, onDiscard, onSaveAsNew }) {
  const editing = draft.mode === 'edit';
  const invalid = (id) => (fieldErrors[id] ? 'field invalid' : 'field');

  function addExercise() {
    const exercise = newExercise();
    update((d) => ({ ...d, exercises: [...d.exercises, exercise] }));
    setFocusKey(exercise.key);
  }

  return (
    <article className="panel form-panel workout-editor">
      <div className="panel-heading">
        <div>
          <div className="eyebrow">{editing ? 'EDITING A SAVED SESSION' : 'SESSION IN PROGRESS'}</div>
          <h3>{editing ? `Editing ${draft.workoutDate}` : 'Current workout'}</h3>
        </div>
        <span className="form-icon">↗</span>
      </div>

      <div className="form-grid">
        <label className={invalid('date')}>
          Date
          <input type="date" value={draft.workoutDate} onChange={(e) => update((d) => ({ ...d, workoutDate: e.target.value }))} />
        </label>
        <label className="field">
          Session notes (optional)
          <input
            value={draft.notes}
            maxLength={1000}
            placeholder="Energy, sleep, how it felt…"
            onChange={(e) => update((d) => ({ ...d, notes: e.target.value }))}
          />
        </label>
      </div>

      <div className="exercise-list">
        {draft.exercises.map((exercise, index) => (
          <div className="exercise-card" key={exercise.key}>
            <div className="exercise-head">
              <span className="eyebrow">EXERCISE {index + 1}</span>
              <button
                type="button"
                className="delete-button"
                onClick={() => update((d) => ({ ...d, exercises: d.exercises.filter((e) => e.key !== exercise.key) }))}
              >
                Remove exercise
              </button>
            </div>
            <div className="form-grid">
              <label className={invalid(`name:${exercise.key}`)}>
                Name
                <input
                  value={exercise.name}
                  maxLength={100}
                  placeholder="e.g. Incline dumbbell press"
                  autoFocus={exercise.key === focusKey}
                  onChange={(e) => updateExercise(exercise.key, (ex) => ({ ...ex, name: e.target.value }))}
                />
              </label>
              <label className="field">
                Notes (optional)
                <input
                  value={exercise.notes}
                  maxLength={500}
                  placeholder="Form cues, tempo, 70s per side…"
                  onChange={(e) => updateExercise(exercise.key, (ex) => ({ ...ex, notes: e.target.value }))}
                />
              </label>
            </div>

            <div className="set-table" role="group" aria-label={`Sets for exercise ${index + 1}`}>
              <div className="set-row set-header">
                <span>SET</span>
                <span>REPS</span>
                <span>KG</span>
                <span />
              </div>
              {exercise.sets.map((set, setIndex) => (
                <div className="set-row" key={set.key}>
                  <span className="set-index">{setIndex + 1}</span>
                  <input
                    className={fieldErrors[`reps:${set.key}`] ? 'invalid' : ''}
                    inputMode="numeric"
                    aria-label={`Reps, set ${setIndex + 1}`}
                    value={set.reps}
                    placeholder="reps"
                    onChange={(e) =>
                      updateExercise(exercise.key, (ex) => ({
                        ...ex,
                        sets: ex.sets.map((s) => (s.key === set.key ? { ...s, reps: e.target.value } : s)),
                      }))
                    }
                  />
                  <input
                    className={fieldErrors[`weight:${set.key}`] ? 'invalid' : ''}
                    inputMode="decimal"
                    aria-label={`Weight in kg, set ${setIndex + 1}`}
                    value={set.weightKg}
                    placeholder="0"
                    onChange={(e) =>
                      updateExercise(exercise.key, (ex) => ({
                        ...ex,
                        sets: ex.sets.map((s) => (s.key === set.key ? { ...s, weightKg: e.target.value } : s)),
                      }))
                    }
                  />
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Remove set ${setIndex + 1}`}
                    onClick={() =>
                      updateExercise(exercise.key, (ex) => ({ ...ex, sets: ex.sets.filter((s) => s.key !== set.key) }))
                    }
                  >
                    ×
                  </button>
                </div>
              ))}
              {fieldErrors[`sets:${exercise.key}`] && (
                <div className="form-message error">{fieldErrors[`sets:${exercise.key}`]}</div>
              )}
              <button
                type="button"
                className="button button-outline add-set"
                onClick={() =>
                  updateExercise(exercise.key, (ex) => ({ ...ex, sets: [...ex.sets, newSet(ex.sets[ex.sets.length - 1])] }))
                }
              >
                ＋ Add set
              </button>
            </div>
          </div>
        ))}
      </div>

      {fieldErrors.exercises && <div className="form-message error">{fieldErrors.exercises}</div>}
      <button type="button" className="button button-dark full-width add-exercise" onClick={addExercise}>
        ＋ Add exercise
      </button>

      {Object.keys(fieldErrors).length > 0 && (
        <ul className="form-message error error-list">
          {Object.entries(fieldErrors)
            .slice(0, 4)
            .map(([id, message]) => (
              <li key={id}>{message}</li>
            ))}
        </ul>
      )}

      <div className="form-actions editor-actions">
        <span className={`form-message ${status.error ? 'error' : ''}`}>{status.error}</span>
        <div className="button-row">
          <button type="button" className="button button-outline" onClick={onDiscard} disabled={status.saving}>
            Discard
          </button>
          {status.missing ? (
            <button type="button" className="button button-primary" onClick={onSaveAsNew} disabled={status.saving}>
              Save as new workout
            </button>
          ) : (
            <button type="button" className="button button-primary" onClick={onSubmit} disabled={status.saving}>
              {status.saving ? 'Saving…' : editing ? 'Save changes' : 'Finish workout'} <span>→</span>
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

function HistoryRow({ workout, editDisabled, deleteDisabled, onEdit, onDelete }) {
  const totalSets = workout.exercises.reduce((n, e) => n + e.sets.length, 0);
  const names = workout.exercises.map((e) => e.name);
  const preview = names.slice(0, 3).join(', ') + (names.length > 3 ? ', …' : '');
  return (
    <details className="history-row">
      <summary>
        <span className="history-date">{workout.workoutDate}</span>
        <span className="history-main">
          <strong>{preview}</strong>
          <span className="activity-sub">
            {plural(workout.exercises.length, 'exercise', 'exercises')} · {plural(totalSets, 'set', 'sets')}
          </span>
        </span>
      </summary>
      <div className="history-detail">
        {workout.notes && <p className="history-notes">{workout.notes}</p>}
        {workout.exercises.map((e, i) => (
          <div className="history-exercise" key={i}>
            <strong>{e.name}</strong>
            <span>{e.sets.map((s) => (s.weightKg ? `${s.reps} @ ${fmt(s.weightKg, 1)} kg` : `${s.reps} reps`)).join(' · ')}</span>
            {e.notes && <span className="activity-sub">{e.notes}</span>}
          </div>
        ))}
        <div className="history-actions">
          <button
            type="button"
            className="button button-outline"
            onClick={onEdit}
            disabled={editDisabled}
            title={editDisabled ? 'Finish or discard your current workout first' : undefined}
          >
            Edit
          </button>
          <button type="button" className="delete-button" onClick={onDelete} disabled={deleteDisabled}>
            Delete
          </button>
        </div>
      </div>
    </details>
  );
}
