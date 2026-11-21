// The single local workout draft (spec §9.4). Pure functions only, so this logic is easy to test later.
//
// Inputs are kept as strings so a half-typed value such as "17." survives a refresh; parsing happens only
// on submit. A failed submit never clears the draft.

import { readJson, remove, writeJson, writeRaw } from './storage.js';
import { numberToInput, toNumber } from './format.js';

export const DRAFT_KEY = 'fitlocal.workoutDraft.v1';
export const CORRUPT_KEY = 'fitlocal.workoutDraft.corrupt';

function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  // Fallback for non-secure contexts (plain-HTTP access from another device).
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

let counter = 0;
function newKey(prefix) {
  counter += 1;
  return `${prefix}${Date.now().toString(36)}${counter}`;
}

function nowIso() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** A new set pre-fills reps and weight from the previous set of the same exercise. */
export function newSet(previous) {
  return { key: newKey('s'), reps: previous?.reps ?? '', weightKg: previous?.weightKg ?? '' };
}

export function newExercise() {
  return { key: newKey('k'), name: '', notes: '', sets: [newSet()] };
}

export function newDraft(today) {
  return {
    mode: 'new',
    workoutId: null,
    submissionId: uuid(),
    workoutDate: today,
    notes: '',
    exercises: [newExercise()],
    updatedAt: nowIso(),
  };
}

/** Loads a submitted workout into the editor; numbers become strings. */
export function draftFromWorkout(workout) {
  return {
    mode: 'edit',
    workoutId: workout.id,
    submissionId: null,
    workoutDate: workout.workoutDate,
    notes: workout.notes || '',
    exercises: workout.exercises.map((e) => ({
      key: newKey('k'),
      name: e.name,
      notes: e.notes || '',
      sets: e.sets.map((s) => ({ key: newKey('s'), reps: numberToInput(s.reps), weightKg: numberToInput(s.weightKg) })),
    })),
    updatedAt: nowIso(),
  };
}

/** For "Save as new workout" after the original was deleted elsewhere. */
export function asNewDraft(draft) {
  return { ...draft, mode: 'new', workoutId: null, submissionId: uuid() };
}

export function touch(draft) {
  return { ...draft, updatedAt: nowIso() };
}

/** A draft with no exercises and empty notes counts as no draft. */
export function isEmptyDraft(draft) {
  return !draft || (draft.exercises.length === 0 && !draft.notes.trim());
}

/**
 * Returns { draft } or { draft: null, corruptRaw } when stored JSON is unreadable.
 * A corrupt draft is moved aside (never silently overwritten) so it can be recovered by hand.
 */
export function loadDraft() {
  const result = readJson(DRAFT_KEY);
  if (!result.ok) {
    writeRaw(CORRUPT_KEY, result.raw);
    remove(DRAFT_KEY);
    return { draft: null, corrupt: true };
  }
  const draft = result.value;
  const valid = draft && typeof draft === 'object' && Array.isArray(draft.exercises);
  if (draft && !valid) {
    writeRaw(CORRUPT_KEY, JSON.stringify(draft));
    remove(DRAFT_KEY);
    return { draft: null, corrupt: true };
  }
  return { draft: valid && !isEmptyDraft(draft) ? draft : null, corrupt: false };
}

export function persistDraft(draft) {
  if (isEmptyDraft(draft)) remove(DRAFT_KEY);
  else writeJson(DRAFT_KEY, draft);
}

/**
 * Client-side checks before submit (the server checks again). Returns { errors, payload }:
 * errors maps a field id to a message; payload is the request body when there are no errors.
 */
export function validateDraft(draft) {
  const errors = {};
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.workoutDate || '')) errors.date = 'Pick a date';
  if (draft.exercises.length === 0) errors.exercises = 'Add at least one exercise';
  const exercises = draft.exercises.map((e, i) => {
    if (!e.name.trim()) errors[`name:${e.key}`] = `Exercise ${i + 1} needs a name`;
    if (e.sets.length === 0) errors[`sets:${e.key}`] = `Exercise ${i + 1} needs at least one set`;
    const sets = e.sets.map((s, j) => {
      const reps = toNumber(s.reps);
      if (reps === null || !Number.isInteger(reps) || reps < 1 || reps > 1000) {
        errors[`reps:${s.key}`] = `Reps must be a whole number from 1 to 1000 (exercise ${i + 1}, set ${j + 1})`;
      }
      const weight = String(s.weightKg).trim() === '' ? 0 : toNumber(s.weightKg);
      if (weight === null || weight < 0 || weight > 2000) {
        errors[`weight:${s.key}`] = `Weight must be 0 to 2000 kg (exercise ${i + 1}, set ${j + 1})`;
      }
      return { reps, weightKg: weight };
    });
    return { name: e.name.trim(), notes: e.notes.trim(), sets };
  });
  if (Object.keys(errors).length > 0) return { errors, payload: null };
  return { errors, payload: { workoutDate: draft.workoutDate, notes: draft.notes.trim(), exercises } };
}

export function sortWorkouts(list) {
  return [...list].sort((a, b) => b.workoutDate.localeCompare(a.workoutDate) || b.id - a.id);
}
