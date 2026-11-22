-- Tally schema (SQLite). Applied on every startup by Spring SQL init; must stay idempotent.
-- No migration tool: CREATE TABLE IF NOT EXISTS never alters an existing table.
-- Changing this file? Regenerate data/tally.db.example: start.bat --create-sample-db

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS workouts (
  id            INTEGER PRIMARY KEY,
  workout_date  TEXT NOT NULL,
  notes         TEXT NOT NULL DEFAULT '',
  submission_id TEXT UNIQUE,                 -- idempotent submit (C7)
  created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS workout_exercises (
  id         INTEGER PRIMARY KEY,
  workout_id INTEGER NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
  position   INTEGER NOT NULL,
  name       TEXT    NOT NULL,
  notes      TEXT    NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS workout_sets (
  id          INTEGER PRIMARY KEY,
  exercise_id INTEGER NOT NULL REFERENCES workout_exercises(id) ON DELETE CASCADE,
  set_no      INTEGER NOT NULL,
  reps        INTEGER NOT NULL,
  weight_kg   REAL    NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS meals (
  id         INTEGER PRIMARY KEY,
  meal_date  TEXT NOT NULL,
  food       TEXT NOT NULL,
  calories   REAL NOT NULL DEFAULT 0,
  protein_g  REAL NOT NULL DEFAULT 0,
  carbs_g    REAL NOT NULL DEFAULT 0,
  fat_g      REAL NOT NULL DEFAULT 0,
  notes      TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS weigh_ins (
  id         INTEGER PRIMARY KEY,
  weigh_date TEXT NOT NULL,
  weight_kg  REAL NOT NULL,
  notes      TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

-- Discord messages already logged (discord-ingest E2). Written in the same transaction as their entries.
CREATE TABLE IF NOT EXISTS ingested_messages (
  message_id  TEXT PRIMARY KEY,              -- Discord snowflake as text
  ingested_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workouts_date      ON workouts(workout_date);
CREATE INDEX IF NOT EXISTS idx_exercises_workout  ON workout_exercises(workout_id, position);
CREATE INDEX IF NOT EXISTS idx_sets_exercise      ON workout_sets(exercise_id, set_no);
CREATE INDEX IF NOT EXISTS idx_meals_date         ON meals(meal_date);
CREATE INDEX IF NOT EXISTS idx_weigh_ins_date     ON weigh_ins(weigh_date);
