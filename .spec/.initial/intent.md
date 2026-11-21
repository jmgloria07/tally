# FitLocal — Phase 1 Intent

FitLocal is a personal fitness tracker with a coach that runs on a local LLM. It serves one user, is self-hosted on a Windows PC, and can be reached only over Tailscale.

This file gives the goals and the main design choices. [spec.md](spec.md) is the detailed, normative design, and [plan.md](plan.md) is the build plan. Where this file and spec.md differ, spec.md wins.

**Guiding rule: don't over-engineer.** No unnecessary dependencies, no redundant state management, no unnecessary API calls.

## Layout

```
fitlocal/
  backend/     Spring Boot (JDK 21, Maven wrapper)
  frontend/    React + Vite
  data/        runtime DB + signing key (gitignored, except the empty example DB)
  start.bat
  README.md, intent.md, spec.md, plan.md
```

## Stack

- **Backend:** Spring Boot 4 on JDK 21, Maven wrapper (`mvnw`). Spring Web MVC, Spring Security, Spring Data JPA, `sqlite-jdbc` (xerial), `hibernate-community-dialects` (SQLite dialect).
- **Frontend:** React + Vite. Runtime dependencies are only `react`, `react-dom`, `marked` and `dompurify`, all bundled. No router (the active tab lives in the URL hash, so a refresh keeps it), no state library, no chart library.
- **Packaging:** one jar. The Vite build output is served as Spring static resources. The app binds to `127.0.0.1:8765`, and Tailscale Serve proxies to that address. In dev, Vite proxies `/api` to Spring.
- **LLM:** LM Studio's OpenAI-compatible API at `http://127.0.0.1:1234/v1`, called with the JDK `HttpClient`.

## Database

- **SQLite only**, with native types (`INTEGER`, `REAL`, `TEXT`). Supporting other databases is not a goal.
- The schema is a committed `schema.sql` using `CREATE TABLE IF NOT EXISTS`, applied on startup by Spring's SQL init. **No Flyway.** Hibernate never creates or alters the schema (`ddl-auto=none`).
- Dates and timestamps are stored as ISO text, so they sort and compare correctly.
- Runtime DB: `data/fitlocal.db` (gitignored).
- **Empty sample DB:** `data/fitlocal.db.example` is committed, with the full schema and empty tables. A command generates it from `schema.sql` (it is never built by hand), and a test fails the build if the two drift apart. It is used for resets (`copy data\fitlocal.db.example data\fitlocal.db`).

### Tables
- `users`: id, username (unique), password_hash (BCrypt), created_at.
- `workouts` (one session): id, workout_date, notes, submission_id (unique, makes a retried submit safe), created_at.
- `workout_exercises`: id, workout_id → workouts, position, name, notes.
- `workout_sets`: id, exercise_id → workout_exercises, set_no, reps, weight_kg.
- `meals`: id, meal_date, food, calories, protein_g, carbs_g, fat_g, notes, created_at.
- `weigh_ins`: id, weigh_date, weight_kg, notes, created_at. There are no body-fat or muscle-mass columns. Those readings go in the free-text notes (e.g. `BF: 17.5%, MM: 37%`).
- `settings`: key/value with plain-text values. Keys: `calories_target`, `protein_target`, `carbs_target`, `fat_target`, `goal`, `dietary_preferences`, `injuries_limitations`, `coaching_style`. **Defaults live in code, not in the DB** (2000 kcal, 150 g protein, 220 g carbs, 60 g fat, a generic goal, empty dietary preferences and injuries, a practical coaching style). Rows are written only when Goals & Preferences is saved, so a fresh DB and the sample DB have an empty `settings` table.

The data is for a single user: no data table has a `user_id`. Login is only a gate.

Not in phase 1: timed or duration sets (use notes, e.g. side planks "70s per side"), and a meal-type column (use notes).

## Authentication

- Spring Security. No registration and no login rate limiting (only the tailnet can reach the app).
- Users are created by hand: `start.bat --create-user <username>` prompts for the password and stores a BCrypt hash. Running it for an existing user offers a password reset.
- Login sets an **HMAC-signed token valid for 30 days** in a cookie with `HttpOnly; Secure; SameSite=Strict`. Nothing is kept in JS-readable storage. No JWT library. The signature also covers the user's password hash, so a password reset signs out that user's devices.
- The signing key is generated randomly on first run and stored in `data/` (gitignored). Deleting it signs out every device. A logout endpoint signs out one device.
- Everything under `/api/**` except login requires auth. On a 401 the frontend shows the login screen **without discarding any local draft**.

## UI

Six tabs: **Overview, Workout, Nutrition, Body Metrics, AI Coach, Goals & Preferences.**

- Mobile-first and responsive: a bottom tab bar on phones, a sidebar on desktop. Touch-friendly targets (≥44 px) and inputs.
- Dark theme with this palette: `--bg #0e1114`, `--panel #171c20`, `--green #c5f56b`, `--orange #ffb26d`, `--blue #8bbbf7`, `--violet #b4a0f5` (full list in spec §9.9). System fonts only.
- A hand-drawn SVG weight trend chart over the last 30 days. Coach replies are rendered as Markdown and sanitized.

### Overview
Today's calories and macros against targets, training days in the last 7, latest weight and the 30-day trend chart, weekly average calories and protein per logged day, recent activity, and coach shortcut chips.

### Workout
- Build a session: add exercises, and give each exercise its own list of sets with **reps and weight per set**. A new set pre-fills from the previous set.
- **Local draft:** the whole in-progress session is kept in `localStorage` and saved on every change. It survives a refresh, accidental navigation and closing the browser. "Finish workout" submits it once (`POST /api/workouts`, with the full session in the body). The draft is cleared only after the server confirms success. A failed submit (offline, PC down, 401) keeps the draft.
- **Edit after submit:** load a submitted workout into the same editor and save with `PUT /api/workouts/{id}`, which replaces its exercises and sets. **There is one draft slot**, shared by new workouts and edits. If a draft exists, it must be submitted or discarded before another workout can be edited.
- Delete workouts and browse the history list.

### Nutrition / Body Metrics
Log, list, **edit** (`PUT`) and delete meals and weigh-ins. Body Metrics records weight plus free-text notes.

### Goals & Preferences
Edit the targets and the four free-text fields. These are the context the coach receives.

### AI Coach
- Chat. Each request includes the last 4 weeks of logs plus the goals and preferences, assembled on the server.
- Chat history: the last 12 messages are kept in `localStorage` only (no chat table), sent with each request and trimmed again on the server.
- **Weekly review** is a prompt chip that goes through the same chat endpoint. There is no separate review endpoint.
- **No streaming** in phase 1. One blocking call with a 180 s timeout (replies normally take under a minute).
- Three quick-prompt chips: "Weekly review", "Plan a workout", "Meal idea".
- A "View context" panel shows exactly what the model receives.

## LLM integration

- Target models: **gpt-oss-20B** and **Qwen3 14B**, run with about a **20K context**.
- The coach uses only a model that LM Studio reports as **loaded**, so a question never makes LM Studio load a model on its own. An optional `LM_STUDIO_MODEL` is preferred when it is loaded. `LM_STUDIO_BASE_URL` defaults to `http://127.0.0.1:1234/v1`.
- Reasoning output is stripped from replies (`<think>…</think>` blocks and gpt-oss channel markup). Any separate `reasoning_content` field is ignored.
- **Making sure the model reads the context correctly** (the main quality requirement):
  - Send the context as **compact labeled plain text, not JSON**, with clear section headers, explicit units (kg, kcal, g), and ISO dates with weekdays. Start with the current local date and time.
  - Example workout line: `2026-11-04 (Wed) Incline DB Press — sets: 8 reps @ 17.5 kg, 8 @ 17.5, 8 @ 17.5, 7 @ 17.5 — note: "Hard sets"`.
  - **The server computes every number a review needs** (daily totals, differences from targets, counts, averages, weight change), and the model is told to quote them. It is never asked to do arithmetic.
  - Every list is oldest first, and every line starts with its own date.
  - Today is a separate "in progress" section, kept out of the counts, so a day that has barely started is never read as a missed day.
  - When a category has no data in the window, say so explicitly.
  - Goals and preferences go in the system prompt, each field clearly labeled, along with the coaching and safety rules and the formatting rules (compact Markdown, `##`/`###` only).
  - Expected size: ~3–5K tokens of context plus up to ~7K of history, well within 20K.

## Start script

`start.bat` (Windows only) is the single launcher:
1. Find LM Studio's `lms` CLI, start LM Studio if it isn't running, and make sure the API is up on `127.0.0.1:1234`. If a chat model is already loaded, use it. Otherwise load the configured model (its key is saved in a local config file on first run) with a 20K context.
2. Build the jar if it's missing, or when `--rebuild` is passed (frontend build, then `mvnw package`).
3. Run the jar on `127.0.0.1:8765`.
4. Pass these subcommands through to the jar, without starting LM Studio: `--create-user <name>`, `--create-sample-db`.

Requires JDK 21 and Node on PATH (Node only for building).

## Portfolio deliverables

- `.gitignore`: `data/*` except `data/fitlocal.db.example`, build outputs, `node_modules`, and the local start config. **No personal health, allergy or log data is ever committed.**
- README: what the app is, architecture, security model (localhost bind + Tailscale + cookie auth), and setup (install JDK, create a user, start).
- Focused backend tests: auth (login, 30-day token, rejection when the cookie is missing or tampered with), workout submit/edit, and the coach context text.

## Out of scope for phase 1

Streaming, Flyway, Docker, CI, demo seed data, importing data from other apps or databases, multi-user data scoping, food database or barcode scanning, timed sets, meal-type column, login rate limiting, databases other than SQLite, cloud deployment of any kind.
