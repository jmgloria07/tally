# FitLocal Phase 1: Requirements & Design Spec

> **Note:** The app was renamed from FitLocal to **Tally** after this spec was written. This document is kept as the original record; read `fitlocal` as `tally` in package names, file names and config keys.

| | |
|---|---|
| **Status** | Approved. All open decisions resolved (see §1 and §13). |
| **Source** | [intent.md](intent.md) (goals and main design choices) |
| **Plan** | [plan.md](plan.md) |
| **Date** | 2026-11-21 |

This is the normative design for FitLocal phase 1. **Where this spec and intent.md disagree, this spec wins.** §1 records each design decision and its reason. Code comments cite those decisions by ID (for example `C7`, `D6`) and sections by number (for example `§8.4`), so keep both stable when editing.

Guiding rule: **don't over-engineer.** Every design choice below was checked against it. Where the design adds something beyond the bare feature list, it says so and gives the reason.

---

## Contents

1. [Design decisions](#1-design-decisions)
2. [Scope](#2-scope)
3. [Architecture](#3-architecture)
4. [Data model](#4-data-model)
5. [CLI subcommands](#5-cli-subcommands)
6. [Authentication & security](#6-authentication--security)
7. [HTTP API](#7-http-api)
8. [LLM integration](#8-llm-integration)
9. [Frontend](#9-frontend)
10. [start.bat](#10-startbat)
11. [Repository hygiene & README](#11-repository-hygiene--readme)
12. [Testing](#12-testing)
13. [Decision index](#13-decision-index)
14. [Acceptance checklist](#14-acceptance-checklist)
15. [Suggested build order](#15-suggested-build-order)

---

## 1. Design decisions

### 1A. Stack and data (C1–C6)

**C1. Spring Boot 4.x on JDK 21.**
Use the latest Spring Boot 4.x patch. The 3.x line is past its open-source support window.
- Generate the project with Spring Initializr so it uses Boot 4's starter names. Boot 4 split some starters into modules (for example `spring-boot-starter-webmvc`, plus separate test starters).
- `hibernate-community-dialects` must match the Hibernate 7 version that Boot manages. Don't pin it separately.

**C2. SQLite only.**
No single `CREATE TABLE` syntax auto-generates IDs on both SQLite and standard databases, so the schema targets SQLite only. Moving to another database may never happen and isn't a phase 1 concern.
- `schema.sql` uses SQLite's native types (`INTEGER`, `REAL`, `TEXT`) and `id INTEGER PRIMARY KEY` (a rowid alias, auto-assigned).
- It uses `CREATE TABLE IF NOT EXISTS`, applied by Spring SQL init, without Flyway.

**C3. `settings` columns are `key` / `value`.**
SQLite accepts both as column names, so there's no reason to rename them.

**C4. Settings defaults live in code.**
`schema.sql` never seeds settings. `SettingsService` merges stored rows over in-code defaults (§4.3), and rows are written only when Goals & Preferences is saved. A fresh DB and the sample DB therefore have an empty `settings` table, and changing a default needs no data change.

**C5. Markdown rendering uses `marked` + `dompurify`.**
Coach replies need real Markdown (headings, lists, tables), and a hand-written renderer would be a larger XSS risk than a maintained one. Both libraries are **npm dependencies bundled by Vite**, never loaded from a CDN. See §9.7.

**C6. Weekly review goes through the chat endpoint.**
- One chip, **Weekly review**, sends the structured review prompt (§8.4) through `/api/coach`.
- It uses the normal chat temperature, **0.55**. There is no separate review endpoint or temperature.

### 1B. Risks and the minimum fix for each (C7–C17)

**C7. Duplicate workouts when a submit is retried. Fix: `submission_id`.**
"A failed submit keeps the draft", combined with mobile-over-Tailscale networking, leads to a predictable failure:
1. The server commits the workout.
2. The response is lost (tunnel drop, phone sleeps).
3. The client sees a failure, and the user taps Finish again.
4. The workout is saved twice.

The fix is a nullable **`workouts.submission_id TEXT UNIQUE`** column:
- The draft generates a UUID once, when it is created.
- A `POST /api/workouts` whose `submissionId` already exists returns the existing workout (`200`) and inserts nothing.

**C8. Context budget and reasoning models.**
- `start.bat` loads a model with `--context-length 20480` (~20K).
- `lms load` on a model that's **already loaded starts a second instance** and uses twice the VRAM. So if *any* chat model is already loaded, `start.bat` uses it and loads nothing (D6, §10).
- gpt-oss-20B and Qwen3 (with thinking on) spend output tokens on reasoning. Budget: ~3–5K context + ≤~7K history + a ~1K system prompt leaves ≥~7K for reasoning and the answer. That's usually enough, but a long reasoning pass can hit the limit and return an empty or truncated answer. *Mitigation:* the server caps history by characters (§8.5), doesn't set `max_tokens` (so the model isn't cut off before its final answer), and returns a clear error if the reply is empty after stripping reasoning. Tuning options if this becomes a problem: shorter history, Qwen3 `/no_think`, or lower gpt-oss reasoning effort in LM Studio. None of these is in phase 1.

**C9. `/v1/models` doesn't say which models are loaded.**
With LM Studio's just-in-time loading enabled, `/v1/models` lists *every downloaded* model, not just loaded ones. Picking "the first" can name an arbitrary model that then gets JIT-loaded with a small default context.
- *Decision:* the app asks LM Studio's REST API (`GET /api/v0/models`, which reports `state: loaded | not-loaded`) on every request and uses only a loaded model (D6, §8.2). `start.bat` loads a model only when none is loaded (§10).
- `start.bat` uses no custom identifier. A model it loads keeps its own key as its name, like a model loaded from the LM Studio app, so LM Studio's model list and FitLocal's requests always use the same name.

**C10. No CSRF token.**
Spring Security turns on CSRF tokens by default, and a token-in-header scheme would need a JS-readable cookie or an extra endpoint.
- *Decision:* **Disable Spring's CSRF token.** Rely on `SameSite=Strict`: browsers don't send the auth cookie on any cross-site request. As a second layer, every mutating `/api/**` request must have `Content-Type: application/json`, which a cross-site HTML form can't send. This is the standard argument and goes in the README's security model.

**C11. `Secure` cookie vs. plain-HTTP access.**
Tailscale Serve terminates HTTPS and proxies plain HTTP to `127.0.0.1:8765`. The browser only sees HTTPS, so `Secure` works. Direct access on the PC (`http://127.0.0.1:8765`) and the Vite dev server (`http://localhost:5173`) also work in Chromium and Firefox, because both treat localhost as a secure context. The flag is always set, with no dev toggle. *Risk:* a browser that doesn't apply the localhost exception won't keep the cookie over plain HTTP. Use the Tailscale URL in that case.

**C12. SQLite + Spring defaults + a 180 s blocking LLM call.**
Spring's default `open-in-view=true` holds a DB connection for the whole request, so one 3-minute coach call would hold a connection the whole time. With SQLite, more connections mean `SQLITE_BUSY` errors on concurrent writes.
- *Decision:* `spring.jpa.open-in-view=false`. The coach context is assembled in a short read-only transaction that **ends before** the HTTP call to LM Studio starts. Use Hikari `maximum-pool-size=1` (SQLite allows one writer anyway, and every transaction here takes milliseconds), plus `busy_timeout=5000` as a safety net.

**C13. SQLite date storage with xerial.**
By default, `sqlite-jdbc` binds `java.sql.Date`/`Timestamp` as **epoch milliseconds**, not ISO text. ISO text (`2026-11-04`, `2026-11-04T17:58:22`) is readable and sorts correctly.
- *Decision:* Date and timestamp columns are `TEXT`. Entities use `LocalDate`/`LocalDateTime` with two `@Converter(autoApply = true)` classes that convert to and from ISO strings, so `ORDER BY` and range queries work.

**C14. No Flyway: schema changes don't reach existing DBs.**
`CREATE TABLE IF NOT EXISTS` never alters an existing table. Adding a column later means a hand-written `ALTER` or a reset. That's acceptable; migration tooling can come later if it's ever needed. It goes in the README under "Changing the schema".

**C15. Keeping the sample DB in sync with `schema.sql`.**
Generating the sample DB from `schema.sql` doesn't by itself stop drift: someone can edit `schema.sql` and forget to regenerate.
- *Decision:* A test (§12) builds a fresh DB from `schema.sql` and checks it against the committed `data/fitlocal.db.example` (same `sqlite_master` DDL, all tables empty). Drift then fails the build.

**C16. Logout and revocation.**
The cookie is `HttpOnly`, so JS can't clear it, and deleting the signing key logs out *all* devices.
- *Decision (small additions):*
  - `POST /api/auth/logout` clears the cookie on that device.
  - `--create-user <existing>` offers a **password reset**.
  - The token's HMAC also covers the user's current `password_hash` (§6.2), so **resetting a password logs out that user's devices**. Deleting the key still logs out everyone.

**C17. iOS storage eviction.**
Safari can delete script-writable storage (`localStorage`) for sites that haven't been used for 7 days, unless the site is added to the Home Screen. Drafts and chat history could then disappear. They're short-lived, so this is acceptable. The README suggests adding the app to the Home Screen. The HttpOnly auth cookie isn't affected by this cap.

### 1C. Interpretations (I1–I9)

| # | Question | Decision |
|---|---|---|
| I1 | What the Overview shows | Stat cards, macro bars, weekly averages, the weight chart, a **Recent activity** panel and a coach panel. `GET /api/dashboard` returns the 7 most recent activity items, so it's still one API call. The coach panel has its own chips and links to Coach. |
| I2 | Six tabs in a phone bottom bar with ≥44 px targets | Each tab gets 53+ px of width at 320 px, which works, but the full labels don't fit. The bottom bar uses icon + short label (**Overview, Workout, Food, Body, Coach, Goals**). The sidebar uses full names. |
| I3 | "Today" / "last 7 days" | Use the **server's local calendar date** (`ZoneId.systemDefault()` via an injectable `Clock`). Window = today and the 6 days before it (30-day windows: today and the 29 days before it). Upper bound = today, so future-dated rows are excluded. |
| I4 | Multiple sessions on one date | Allowed, with no unique constraint. "Workout days" counts distinct dates. |
| I5 | Workout history size | `GET /api/workouts` returns the **100** most recent sessions in full detail, so editing needs no extra call. No paging in phase 1, so older sessions can't be edited from the UI. |
| I6 | Request validation | Hand-written checks in DTO compact constructors, without `spring-boot-starter-validation`, which keeps the dependency list short. Swapping in Bean Validation later is reasonable. |
| I7 | Cross-device edits | Last write wins. Editing a workout that was deleted on another device returns `404`. The UI keeps the draft and offers "Save as new workout" or "Discard". |
| I8 | Token lifetime | Fixed 30 days from login, not sliding. Log in again after 30 days. |
| I9 | Logout and local data | Logout leaves the `localStorage` draft and chat history in place (consistent with "never discard a draft"). |

### 1D. Coach context, model selection and chart (D1–D6)

**D1, D2.** *Superseded by D5.* An earlier version sent a rolling 30-day window with 30 daily nutrition lines.

**D3. Weight chart.** An SVG line chart with one slot per day for 30 days and a y-axis fitted to the data, not starting at 0, so changes of a few tenths of a kg are visible (§9.8).

**D4. "Today" in the coach context.** The model gets the current local time. Today is a separate "in progress" section and is excluded from all counts and averages; the window is made of completed days only. Nutrition lines use "N of target". `GET /api/coach/context` plus a "View context" panel show exactly what the model receives. The day boundary stays at midnight (no rollover hour).

**D5. Coach context layout.** A 28-day window as four 7-day blocks. An AT A GLANCE section with every review count computed by the app (`CoachStats`). Daily lines only for the last 7 completed days; earlier blocks get one summary line each. Every list is oldest first, and every line starts with its date. Workouts are grouped by block. The data-reading rules say: start from the glance, never infer dates from position, today's training counts as this week's. Days logged at exactly the targets are **not** flagged. *Reason:* gpt-oss-20B and Qwen3 14B both misread a 30-line, newest-first daily list on real data, mapping numbers to the wrong dates.

**D6. Model selection.** The app uses only a model LM Studio reports as loaded (`/api/v0/models`): the configured one if loaded, else the first loaded chat model, else a 503. `start.bat` loads its configured model only when nothing is loaded. *Reason:* loading by a fixed identifier missed a model loaded from the LM Studio app, so a second model was loaded on top of it (and failed for lack of VRAM), and the `/v1/models` fallback could JIT-load the first downloaded model.

---

## 2. Scope

### In scope (phase 1)
Everything in [intent.md](intent.md) except its "Out of scope" list, as detailed in this spec.

### Out of scope (do not build)
Streaming, Flyway, Docker, CI, demo seed data, multi-user data scoping, food DB / barcode scanning, timed sets, meal-type column, login rate limiting, cloud deployment, support for databases other than SQLite, a chat table, a router, a state library, a chart library, a JWT library.

### Non-functional requirements

| Area | Requirement |
|---|---|
| Network exposure | HTTP listener on `127.0.0.1:8765` only. Remote access only via Tailscale Serve (HTTPS). |
| Privacy | No personal data in git, logs, or test fixtures. No third-party network calls from the browser (no CDNs, no web fonts). The server's only outbound call is to LM Studio on `127.0.0.1`. |
| Logging | Never log request/response bodies, LLM prompts, LLM replies, passwords, or tokens. Log method, path, status, and duration only. |
| Mobile | Usable one-handed on a phone. Touch targets ≥44 px. Inputs ≥16 px font (prevents iOS zoom-on-focus). |
| Footprint | Backend dependencies: the stack in intent.md plus test starters. Frontend runtime dependencies: `react`, `react-dom`, `marked`, `dompurify` only. |

---

## 3. Architecture

### 3.1 Runtime view

```
 Phone / laptop (tailnet)                     Windows PC
 ┌──────────────┐  HTTPS   ┌───────────────┐  HTTP   ┌──────────────────────────────┐
 │ Browser      │─────────▶│ Tailscale     │────────▶│ fitlocal.jar  127.0.0.1:8765 │
 │ React SPA    │◀─────────│ Serve         │         │  ├─ static/ (Vite build)     │
 │ localStorage:│          └───────────────┘         │  ├─ /api/** (Spring MVC)     │
 │  draft, chat │                                    │  ├─ Spring Security filter   │
 └──────────────┘                                    │  └─ JPA ─▶ data/fitlocal.db  │
                                                     │        HttpClient            │
                                                     │          │                   │
                                                     │          ▼                   │
                                                     │  LM Studio 127.0.0.1:1234/v1 │
                                                     └──────────────────────────────┘
```

### 3.2 Repository layout

```
fitlocal/
  backend/
    mvnw, mvnw.cmd, .mvn/
    pom.xml
    src/main/java/app/fitlocal/
      FitLocalApplication.java     main(): dispatch CLI subcommand or start web
      auth/        SecurityConfig, PasswordConfig, AuthTokenService, AuthCookieFilter,
                   SigningKeyStore, AuthService, AuthController, User, UserRepository
      workout/     Workout, WorkoutExercise, WorkoutSet, WorkoutRepository,
                   WorkoutService, WorkoutController, WorkoutDto
      meal/        Meal, MealRepository, MealService, MealController, MealDto
      weighin/     WeighIn, WeighInRepository, WeighInService, WeighInController, WeighInDto
      settings/    Setting, Settings, SettingRepository, SettingsService,
                   SettingsController, SettingsDto
      dashboard/   DashboardService, DashboardController, DashboardDto
      coach/       CoachController, CoachService, CoachContextBuilder, CoachStats,
                   CoachPrompts, CoachHistory, CoachDto, ChatMessage, LmStudioClient,
                   ModelPicker, ReasoningStripper, LlmException
      cli/         CliCommand, CreateUserCommand, SampleDbCommand
      common/      IsoDateConverter, IsoDateTimeConverter, IsoFormats, ApiErrorHandler,
                   ApiError, Checks, Numbers, ValidationException, NotFoundException,
                   ClockConfig, DataDirInitializer, RequestLoggingFilter
    src/main/resources/
      application.properties
      schema.sql
    src/test/java/app/fitlocal/...
  frontend/
    package.json, vite.config.js, index.html
    src/ (see §9.1)
  data/
    fitlocal.db.example            committed, generated
    (fitlocal.db, signing.key)     gitignored
  start.bat
  README.md, intent.md, spec.md, plan.md
  .gitignore
```

Package root `app.fitlocal` is neutral and doesn't contain the author's name.

### 3.3 Build & packaging

- Vite builds to `frontend/dist/`.
- `pom.xml` adds `../frontend/dist` as an extra resource directory with `targetPath` `static`, so the built UI ends up at `classpath:/static/`. No generated files are written into `backend/src`, and no `frontend-maven-plugin` is used (`start.bat` runs the npm build).
- `<finalName>fitlocal</finalName>` produces `backend/target/fitlocal.jar`.
- `mvnw package` without a frontend build still produces a working API-only jar, so backend tests don't need Node.

### 3.4 Configuration (`application.properties`)

```properties
server.address=127.0.0.1
server.port=8765

fitlocal.data-dir=${FITLOCAL_DATA_DIR:./data}
spring.datasource.url=jdbc:sqlite:${fitlocal.data-dir}/fitlocal.db?foreign_keys=true&busy_timeout=5000
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.datasource.hikari.maximum-pool-size=1

spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.default_batch_fetch_size=64

fitlocal.lm-studio.base-url=${LM_STUDIO_BASE_URL:http://127.0.0.1:1234/v1}
fitlocal.lm-studio.model=${LM_STUDIO_MODEL:}
fitlocal.lm-studio.timeout=180s
```

- `ddl-auto=none` rather than `validate`: Hibernate's schema validator is unreliable against SQLite's loose type names and would give false failures.
- The SQLite dialect is `org.hibernate.community.dialect.SQLiteDialect` from `hibernate-community-dialects`. With Boot 4 this is the Hibernate 7 build; let Boot's BOM manage the version.
- The data dir is relative to the working directory. `start.bat` always `cd`s to the repo root. On startup the app creates `data/` if it's missing.
- `default_batch_fetch_size` prevents N+1 queries when loading workouts → exercises → sets.

---

## 4. Data model

### 4.1 Conventions

| Concern | Rule |
|---|---|
| IDs | `INTEGER PRIMARY KEY` (SQLite rowid alias), `GenerationType.IDENTITY` in JPA. |
| Dates | `TEXT` ISO `YYYY-MM-DD` ↔ `LocalDate` via converter. See C13. |
| Timestamps | `TEXT` ISO local `YYYY-MM-DDTHH:MM:SS` (server local time, seconds precision) ↔ `LocalDateTime`. |
| Decimals | `REAL`. |
| Text | `TEXT`. Length limits are enforced by the application (§7.3). |
| Notes | `NOT NULL DEFAULT ''`, never NULL. |
| Settings values | Plain text. Numbers stored canonically: integral values without a decimal point (`"2000"`), otherwise minimal decimals (`"112.5"`). |

### 4.2 `schema.sql` (normative)

```sql
-- FitLocal schema (SQLite). Applied on every startup by Spring SQL init; must stay idempotent.
-- No migration tool: CREATE TABLE IF NOT EXISTS never alters an existing table.
-- Changing this file? Regenerate data/fitlocal.db.example: start.bat --create-sample-db

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

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workouts_date      ON workouts(workout_date);
CREATE INDEX IF NOT EXISTS idx_exercises_workout  ON workout_exercises(workout_id, position);
CREATE INDEX IF NOT EXISTS idx_sets_exercise      ON workout_sets(exercise_id, set_no);
CREATE INDEX IF NOT EXISTS idx_meals_date         ON meals(meal_date);
CREATE INDEX IF NOT EXISTS idx_weigh_ins_date     ON weigh_ins(weigh_date);
```

There's deliberately no unique constraint on `(workout_id, position)` or `(exercise_id, set_no)`. Hibernate flushes inserts before deletes, so replacing children inside one flush would violate such a constraint (see §7.4 `PUT`).

### 4.3 JPA mapping notes

- `Workout` 1→N `WorkoutExercise` (`@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true) @OrderBy("position")`). Same pattern for `WorkoutExercise` 1→N `WorkoutSet` (`@OrderBy("setNo")`).
- Don't serialize entities directly. Controllers map entities to DTO records.
- `Setting` is a two-field entity mapped to columns `key` and `value`. SQLite accepts both names unquoted. If Hibernate's generated SQL causes problems, quote them with `@Column(name = "\"key\"")` rather than renaming the columns. `SettingsService` merges stored rows over in-code defaults (defaults live in code, never in the DB):

| Key | Default |
|---|---|
| `calories_target` | `2000` |
| `protein_target` | `150` |
| `carbs_target` | `220` |
| `fat_target` | `60` |
| `goal` | `Build strength and improve body composition` |
| `dietary_preferences` | `` |
| `injuries_limitations` | `` |
| `coaching_style` | `Practical, direct, encouraging; prioritize consistency over perfection.` |

### 4.4 Sample DB

- `start.bat --create-sample-db` (passes through to the jar) **deletes and recreates** `data/fitlocal.db.example`, runs `schema.sql` against it using the same Spring `ScriptUtils` path the app uses, and exits.
- Committed to git. Used for reset: `copy data\fitlocal.db.example data\fitlocal.db`.
- A fresh clone also works without copying, because startup applies `schema.sql` and creates the DB. The sample is still kept for the documented reset workflow.
- The drift test is in §12 (C15).

---

## 5. CLI subcommands

### 5.1 Dispatch

`FitLocalApplication.main(args)`:
- If `args[0]` is `--create-user` or `--create-sample-db`: start Spring with `WebApplicationType.NONE` (no port bound), run the command bean, then `System.exit(code)`. Exit code 0 on success, 1 on any failure, with a one-line message on stderr.
- Otherwise start the web app.
- An unknown `--xyz` flag prints usage and exits 1, so a typo doesn't start the server.

### 5.2 `--create-user <username>`

1. Validate the username: 1–64 chars, `[A-Za-z0-9._-]`.
2. Read the password with `System.console().readPassword()` twice (enter + confirm), minimum 8 chars. If `System.console()` is null (IDE, piped), exit 1 with "run from a terminal". Never echo the password or accept it as an argument.
3. New user: insert with a BCrypt hash (`BCryptPasswordEncoder`, default strength 10).
4. Existing user: ask `User exists. Reset password? [y/N]`. On `y`, update the hash, which revokes that user's tokens (§6.2).

---

## 6. Authentication & security

### 6.1 Spring Security configuration

- `SessionCreationPolicy.STATELESS`. No `JSESSIONID`.
- CSRF disabled (C10). `formLogin`, `httpBasic`, and `logout` DSLs disabled.
- Authorization:
  - `POST /api/auth/login`: permit all.
  - `/api/**`: authenticated.
  - Everything else (static SPA files): permit all.
- Unauthenticated `/api/**` → `401` with JSON body `{"error":"Not signed in"}` (custom `AuthenticationEntryPoint`), never a redirect.
- Mutating `/api/**` requests without `Content-Type: application/json` → `415`. Spring MVC's `consumes` handles this. `DELETE` has no body and is covered by SameSite alone.
- Response headers: keep Spring Security defaults (`Cache-Control: no-store` on API responses, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`), plus:
  `Content-Security-Policy: default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`.
  This works with the Vite build (no inline scripts) and with React `style` props, which are set through the CSSOM and aren't blocked. It backs up DOMPurify on the markdown `innerHTML` path.

### 6.2 Token format (no JWT library)

```
token     = base64url(payload) "." base64url(signature)
payload   = "v1" "|" username "|" expiresAtEpochSeconds
signature = HMAC-SHA256(key, payload + "|" + passwordHash)
```

- `key`: 32 bytes from `SecureRandom`, stored base64 in `data/signing.key`. Created on first startup if it doesn't exist (write to a temp file, then atomic move). An unreadable or malformed key file fails startup with a clear message rather than silently making a new one. Deleting the file logs out every device.
- `passwordHash` is included in the MAC input but **not** in the token. Changing a password therefore invalidates that user's tokens (C16).
- `expiresAt = now + 30 days` (from the injected `Clock`).

### 6.3 `AuthCookieFilter` (`OncePerRequestFilter`)

For `/api/**` only:
1. Read cookie `fitlocal_auth`. If it's missing, continue unauthenticated, and the entry point returns 401.
2. Split the token, decode it, and check the version is `v1` and the parts are well-formed.
3. Check `expiresAt > now`.
4. Load the user by username. Missing user means reject.
5. Recompute the signature and compare with `MessageDigest.isEqual` (constant time).
6. On success, set an `UsernamePasswordAuthenticationToken` in the `SecurityContext`.

Any failure is treated as unauthenticated. Don't explain the reason in the response.

### 6.4 Login / logout

`POST /api/auth/login` `{ "username": "...", "password": "..." }`
- Success → `204` with
  `Set-Cookie: fitlocal_auth=<token>; Path=/; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict`
  (built with Spring's `ResponseCookie`).
- Failure → `401 {"error":"Invalid username or password"}`. For an unknown username, still run one BCrypt comparison against a dummy hash so response timing doesn't reveal which usernames exist.

`POST /api/auth/logout` (authenticated) → `204` with the same cookie and `Max-Age=0`.

There's no "who am I" endpoint. The SPA learns it's signed out when any API call returns 401 (§9.3).

### 6.5 Threat model summary (for README)

| Layer | Protects against |
|---|---|
| Bind to `127.0.0.1` | Anything on the LAN/Wi-Fi. The app isn't reachable off-box except through Tailscale. |
| Tailscale Serve (HTTPS, tailnet ACLs) | The internet. TLS in transit. Only tailnet devices can connect. |
| Cookie auth (BCrypt + HMAC token) | Other tailnet devices/users and other local OS users. |
| `HttpOnly` | Token theft through XSS. |
| `SameSite=Strict` + JSON-only mutations | CSRF. |
| DOMPurify-sanitized markdown + CSP | XSS from LLM output. |
| Not in scope | Login rate limiting (tailnet-only), multi-user isolation, LM Studio's own API (unauthenticated on localhost). |

---

## 7. HTTP API

### 7.1 Conventions

- JSON, UTF-8, **camelCase** field names. Dates are `YYYY-MM-DD` strings. Timestamps are `YYYY-MM-DDTHH:MM:SS`.
- Errors: `{"error": "<human-readable message>"}`. Statuses: `400` validation, `401` not signed in, `404` missing, `409` conflict, `415` wrong content type, `502/503/504` LLM problems (§8.7), `500` unexpected (generic message; details only in the server log, no bodies).
- Create → `201` + entity. Update → `200` + entity. Delete → `204`. Delete of a missing id → `404`.
- Request body limit: 1 MB.

### 7.2 Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Sign in (public) |
| POST | `/api/auth/logout` | Clear cookie |
| GET | `/api/dashboard` | Overview data |
| GET | `/api/workouts?limit=100` | Recent sessions, full detail, newest first |
| POST | `/api/workouts` | Create session (idempotent on `submissionId`) |
| PUT | `/api/workouts/{id}` | Replace session's date, notes, exercises, sets |
| DELETE | `/api/workouts/{id}` | Delete session (cascades) |
| GET | `/api/meals?limit=150` | Recent meals, newest first |
| POST / PUT / DELETE | `/api/meals`, `/api/meals/{id}` | CRUD |
| GET | `/api/weigh-ins?limit=100` | Recent weigh-ins, newest first |
| POST / PUT / DELETE | `/api/weigh-ins`, `/api/weigh-ins/{id}` | CRUD |
| GET | `/api/settings` | Settings merged over defaults |
| PUT | `/api/settings` | Replace all 8 settings |
| POST | `/api/coach` | Chat turn |
| GET | `/api/coach/context` | The system prompt and tracker block the coach would receive now (D4) |
| GET | `/api/health` | LM Studio status |

"Newest first" means `ORDER BY <date> DESC, id DESC`. `limit` is clamped to 1..500.

### 7.3 Validation rules

| Field | Rule |
|---|---|
| any `*Date` | required, valid ISO date |
| `workout.notes` | ≤1000 chars |
| `workout.exercises` | 1..50 |
| `exercise.name` | required after trim, ≤100 |
| `exercise.notes` | ≤500 |
| `exercise.sets` | 1..50 |
| `set.reps` | integer 1..1000 |
| `set.weightKg` | 0..2000, default 0 |
| `submissionId` | optional, UUID format |
| `meal.food` | required, ≤180 |
| `meal.calories` | 0..10000, required |
| `meal.proteinG/carbsG/fatG` | 0..1000, default 0 |
| `meal.notes`, `weighIn.notes` | ≤500 |
| `weighIn.weightKg` | 20..500, required |
| `caloriesTarget` | 500..10000 |
| `proteinTarget` / `fatTarget` | 0..1000 |
| `carbsTarget` | 0..1500 |
| `goal` | ≤2000 |
| `dietaryPreferences` / `injuriesLimitations` / `coachingStyle` | ≤1000 |
| `coach.message` | required after trim, ≤3000 |

All strings are trimmed. The error message names the field in plain words, e.g. `"Reps must be a whole number between 1 and 1000 (exercise 2, set 3)"`.

### 7.4 Shapes

**Workout** (response; the request is the same without `id`/`createdAt`):
```json
{
  "id": 12,
  "workoutDate": "2026-11-18",
  "notes": "",
  "createdAt": "2026-11-18T18:05:02",
  "exercises": [
    { "name": "Incline DB Press", "notes": "Hard sets",
      "sets": [ { "reps": 8, "weightKg": 17.5 }, { "reps": 7, "weightKg": 17.5 } ] }
  ]
}
```
- `POST` body may include `"submissionId": "<uuid>"`. If that id already exists → `200` with the existing workout (not `201`), and nothing is inserted.
- `position` and `setNo` aren't sent. The server takes them from array order (1-based).
- `PUT /api/workouts/{id}`: load the session, set date and notes, `exercises.clear()`, **`flush()`**, append the new exercises and sets, and save. `id`, `createdAt` and `submissionId` don't change. Unknown id → `404`.

**Meal:** `{ id, mealDate, food, calories, proteinG, carbsG, fatG, notes, createdAt }`
**WeighIn:** `{ id, weighDate, weightKg, notes, createdAt }`
**Settings:** `{ caloriesTarget, proteinTarget, carbsTarget, fatTarget, goal, dietaryPreferences, injuriesLimitations, coachingStyle }` (numbers as JSON numbers, text as strings)

**Dashboard:**
```json
{
  "today": "2026-11-20",
  "targets":      { "calories": 2000, "proteinG": 150, "carbsG": 220, "fatG": 60 },
  "todayTotals":  { "calories": 1250, "proteinG": 80,  "carbsG": 140, "fatG": 40 },
  "workoutDaysLast7": 3,
  "latestWeighIn": { "date": "2026-11-15", "weightKg": 72.4 },
  "weightLast30":  [ { "date": "2026-11-02", "weightKg": 72.9 }, { "date": "2026-11-15", "weightKg": 72.4 }, { "date": "2026-11-20", "weightKg": 72.1 } ],
  "weekAvgCaloriesPerLoggedDay": 1820.0,
  "weekAvgProteinPerLoggedDay": 102.0,
  "recentActivity": [
    { "kind": "workout", "date": "2026-11-18", "title": "Workout — 7 exercises", "detail": "Incline DB Press, Lat Pulldown, …" },
    { "kind": "meal",    "date": "2026-11-20", "title": "Oatmeal with whey", "detail": "420 kcal · 32 g protein" },
    { "kind": "weighIn", "date": "2026-11-20", "title": "Body weight: 72.1 kg", "detail": "<notes or 'Weigh-in recorded'>" }
  ]
}
```
`weightLast30` lists every weigh-in of the last 30 days, oldest first; the other weekly figures stay at 7 days. `latestWeighIn` is `null` when there are none. Totals and averages are rounded to 1 decimal. Averages are `0` when there are no logged days. `recentActivity` holds up to 7 items, sorted by date then `createdAt`, newest first.

**Coach:**
```json
// request
{ "message": "Plan my next workout", "history": [ { "role": "user", "content": "…" }, { "role": "assistant", "content": "…" } ] }
// response
{ "answer": "## Next session\n…" }
```

**Health:** `{ "lmStudio": "online" | "offline", "model": "qwen3-14b" | null }`. `model` is what a chat request would use now (§8.2); `null` with `online` means LM Studio is running with no model loaded. It asks LM Studio with a 2 s timeout and never returns an error status.

---

## 8. LLM integration

### 8.1 Client

- JDK `HttpClient`: one shared instance, connect timeout 5 s, request timeout from `fitlocal.lm-studio.timeout` (180 s).
- Jackson (already present through Spring Web) for request and response JSON.

### 8.2 Model resolution

Decision D6: **use only a model that is loaded**, so a coach question can never make LM Studio load one on its own (its just-in-time loading would load whatever model a request names).

1. On every chat request (5 s timeout; no caching, so swapping models in LM Studio takes effect immediately), `GET {root}/api/v0/models`, LM Studio's REST API, where `{root}` is the base URL without `/v1`. Each entry has `id`, `type` (`llm`, `vlm`, `embeddings`, …) and `state` (`loaded` / `not-loaded`).
2. Pick (`ModelPicker`): the configured model (`LM_STUDIO_MODEL`) if it is loaded; otherwise the first loaded `llm` or `vlm`. A configured model that is not loaded is ignored.
3. No chat model loaded → 503 "LM Studio is running but no model is loaded…". No chat request is sent.
4. Fallback for LM Studio versions without the REST API (a 404, or a body without a `data` array): `LM_STUDIO_MODEL` if set, else `data[0].id` from `GET {base}/models`.

### 8.3 Request

```json
{ "model": "<resolved>", "messages": [ system, ...history, user ], "temperature": 0.55, "stream": false }
```
There's one temperature for every message, including the Weekly review chip (C6). No `max_tokens` (C8). The chat request runs **outside** any DB transaction (C12).

### 8.4 Message assembly

`CoachContextBuilder.build(now)` (a `LocalDateTime` from the injected `Clock`) runs in a `@Transactional(readOnly = true)` method and returns two strings, `systemPrompt` and `trackerBlock`. The final user message is `trackerBlock + "\n\nUSER QUESTION\n" + message`.

History contains only raw user questions and assistant answers. Old tracker blocks are never resent, so each turn carries exactly one fresh copy of the data.

#### System prompt

Fixed text from `CoachPrompts`, in this order:

1. **Coach rules** (`COACH_RULES`): persona, use only supplied data, never invent, facts vs. estimates, sustainable habits, no shaming/crash dieting/dehydration/steroids/training through pain, no diagnosis, seek medical advice for alarming symptoms, flag estimates, ask one focused follow-up when data is missing, don't change targets without asking, no guessed loads.
2. **Data-reading rules** (`DATA_READING_RULES`; D4, D5):
   - The TRACKER DATA covers the 28 completed days before today, as four 7-day blocks, plus TODAY SO FAR. Individual meals are listed only for the last 7 completed days and for today.
   - "Start from the AT A GLANCE section. Its counts, averages and changes were computed by the app: quote them as given; never recount or recompute them."
   - "Every line starts with its own date. Use that date; never work out a date from a line's position."
   - "This week" and a weekly review mean the last 7 completed days, with the earlier blocks only for comparison. "Training logged today counts as this week's training."
   - TODAY SO FAR is in progress (check the time): never call today a gap, a missed day, or under target.
   - A completed day with no meals logged is missing data, not fasting or zero intake.
   - In "1250 of 2000 kcal", 1250 is what was eaten and 2000 is the target.
   - If a section says no data was logged, treat that as unknown, not as zero activity.
   - Dates and times are the user's local calendar dates and clock.
   - "When the data states a weight change, use it. Do not describe a weight trend the data does not state."
3. **Formatting rules** (`FORMATTING_RULES`): `##` main heading, `###` subsections, never `####`, no `---`, no HTML or code fences, compact lists, etc.
4. **User profile**, labeled. Multi-line values are indented two spaces so they can't be mistaken for new labels. Empty values are written as `(not provided)`.

```
USER PROFILE (set by the user in Goals & Preferences)
Goal: Build strength and improve body composition
Dietary preferences / restrictions: (not provided)
Injuries / limitations:
  - line one
  - line two
Coaching style: Practical, direct, encouraging
Daily targets: 2000 kcal, protein 150 g, carbs 220 g, fat 60 g
```

#### Tracker block format (normative)

Rules:
- The first line is `Now: YYYY-MM-DD (Weekday) HH:MM local time.` (D4), then the daily targets.
- The window is the **28 completed days before today, as four 7-day blocks, plus today so far** (D5). The blocks end yesterday and are labeled `22–28 days ago`, `15–21 days ago`, `8–14 days ago` and `Last 7 completed days`.
- **Every list is oldest first, and every line starts with its own date** (D5).
- **The AT A GLANCE section states every count a review needs, computed by the app** (`CoachStats`): days logged, meal entries, average calories and protein on logged days, days within 10% of the calorie target (inclusive), days more than 10% under or over (with dates), days at or above the protein target, the lowest-protein day, sessions in the 7 days, sessions logged today, sessions per block, weigh-ins in the 7 days, today's or the latest weigh-in, and the window's weight change.
- **Today is in progress**: it has its own `[TODAY SO FAR …]` section with the time and totals as `N of target (X left)`, and it is excluded from every count and average except the glance's "logged today" training count. Today never uses the `no meals logged` wording. Today's weigh-in is listed in BODY WEIGHT marked `(today)`, since a weigh-in is a complete measurement.
- Only the last 7 completed days get daily nutrition lines; each earlier block gets one summary line, and an `All 28 days` line closes the section.
- Workouts are grouped under a header line per block, with the session count for each.
- Every date at the start of a line is written `YYYY-MM-DD (Ddd)`; inline lists inside the glance use plain `YYYY-MM-DD`.
- Every number carries its unit (kg, kcal, g). Numbers have no thousands separators and at most 1 decimal, with trailing `.0` dropped.
- Differences are written in words (`under` / `over` / `at target`; for today, `left` / `over` / `target reached`), not signs. Nutrition lines read `1250 of 2000 kcal (750 under)`, so the eaten amount and the target can't be confused.
- Every section appears even when empty and says so explicitly. Free text is quoted.

Synthetic example ("now" 2026-11-20 12:00; the golden test in `CoachContextBuilderTest` has the full text):

```
Now: 2026-11-20 (Friday) 12:00 local time.
Daily targets: 2000 kcal, protein 150 g, carbs 220 g, fat 60 g.

TRACKER DATA — the 28 completed days from 2026-10-23 (Fri) to 2026-11-19 (Thu), as four 7-day blocks, plus today so far.
Units: body weight and loads in kg, energy in kcal, macros in grams. Lists are oldest first; every line starts with its own date.

[LAST 7 COMPLETED DAYS AT A GLANCE — 2026-11-13 (Fri) to 2026-11-19 (Thu), computed by the app]
Meals: logged on 3 of 7 days; 4 meal entries (1 to 2 per day).
Calories: average 1683.3 kcal on logged days; within 10% of target on 1 day; more than 10% under on 1 day (2026-11-15); more than 10% over on 1 day (2026-11-16).
Protein: at or above target on 2 of 3 logged days; lowest 20 g on 2026-11-15.
Training: 2 sessions in these 7 days (2026-11-16, 2026-11-18); 1 session logged today. Sessions per block, oldest first: 22–28 days ago 1, 15–21 days ago 0, 8–14 days ago 0, last 7 days 2.
Body weight: 1 weigh-in in these 7 days (72.4 kg on 2026-11-15); today: 72.1 kg. Change: 0.8 kg lower on 2026-11-20 than on 2026-11-02 (3 weigh-ins).

[TODAY SO FAR — 2026-11-20 (Fri), in progress as of 12:00]
Meals today: 2 so far — 1250 of 2000 kcal (750 left); protein 80 of 150 g (70 left); carbs 140 of 220 g (80 left); fat 40 of 60 g (20 left)
2026-11-20 (Fri) Oatmeal with whey — 420 kcal, protein 32 g, carbs 55 g, fat 8 g — note: "1 scoop"
...
Training today: 1 session.
2026-11-20 (Fri) Push-up — sets: 15 reps, 12 (no added weight)

[LAST 7 COMPLETED DAYS — daily nutrition vs targets]
2026-11-13 (Fri): no meals logged
...
2026-11-18 (Wed): 2150 of 2000 kcal (150 over); protein 155 of 150 g (5 over); carbs 210 of 220 g (10 under); fat 72.5 of 60 g (12.5 over); 1 meal
2026-11-19 (Thu): no meals logged

[MEALS — last 7 completed days]
2026-11-15 (Sun) Toast — 600 kcal, protein 20 g, carbs 80 g, fat 15 g
...

[EARLIER BLOCKS — nutrition summaries]
22–28 days ago, 2026-10-23 (Fri) to 2026-10-29 (Thu): meals on 1 of 7 days; average 2000 kcal and protein 150 g on logged days; calories within 10% on 1 day; protein at target on 1 day.
15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): no meals logged.
8–14 days ago, ...
All 28 days: meals on 6 of 28 days; average 1658.2 kcal and protein 106 g on logged days.

[WORKOUTS — by 7-day block, oldest first]
22–28 days ago, 2026-10-23 (Fri) to 2026-10-29 (Thu): 1 session
2026-10-25 (Sun) Squat — sets: 5 reps @ 57.5 kg
15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): no sessions
...
Last 7 completed days, 2026-11-13 (Fri) to 2026-11-19 (Thu): 2 sessions
2026-11-18 (Wed) session note: "Felt strong"
2026-11-18 (Wed) Incline DB Press — sets: 8 reps @ 17.5 kg, 8 @ 17.5, 8 @ 17.5, 7 @ 17.5 — note: "Hard sets"
...
(Today's training is listed under TODAY SO FAR.)

[BODY WEIGHT — oldest first]
2026-11-02 (Mon) 72.9 kg
2026-11-15 (Sun) 72.4 kg — note: "BF: 17.5%, MM: 37%"
2026-11-20 (Fri) 72.1 kg (today)
Change: 0.8 kg lower on 2026-11-20 than on 2026-11-02 (3 weigh-ins).
```

Empty-case lines (exact wording):
- Today, nothing logged: `Nothing logged yet today (the day is in progress).` Only one kind logged: `Meals today: none logged yet.` / `Training today: none logged yet.`
- Glance, no meals in the 7 days: `Meals: none logged in these 7 days.`, then `Calories: no data (no meals logged).` and `Protein: no data (no meals logged).`
- Glance training, no sessions in the 28 days: `No sessions in the 28 completed days; most recent before them: <date>.` or `No sessions on record before today.`
- Glance body weight, none in the 7 days and none today: `latest <kg> on <date>` (from the window, or from before it) or `no weigh-ins on record`. With one weigh-in in the window: `Change: no trend can be computed (fewer than two weigh-ins in the window).`
- Earlier block with no meals: `no meals logged.` The closing line with none at all: `All 28 days: no meals logged.`
- Meals section, none in the last 7 completed days: `No meals logged in the last 7 completed days.`
- Workouts, none in the 28 days: `No workouts logged in the 28 completed days.`
- Body weight, none: `No weigh-ins in this window.` If an earlier weigh-in exists, add `Most recent before this window: 2026-10-02 (Fri) 72.9 kg.` Exactly one: list it, then `Only one weigh-in; no trend can be computed.`

Exercise line rules:
- Session notes, when not empty, go on their own `session note:` line.
- If every set has weight 0, omit `@` and append `(no added weight)`.
- The first set shows `reps` and `kg`. Later sets show only the numbers.

Size caps: 120 itemized meals, 150 exercises, 40 weigh-ins. A cap keeps the newest entries, and `(older entries in this window omitted)` goes at the top of the oldest-first list.

Expected size: ~3–5K tokens (about 2.7K for a typical month of real use). With ≤~7K of history and a ~1K system prompt, that leaves ≥~7K of the 20K context for reasoning and the answer (C8).

#### Quick-prompt chips (text sent as the message)

There are exactly three Coach chips (C6).

| Chip | Prompt |
|---|---|
| Weekly review | "Write my weekly review. Start from the 'at a glance' summary and use its numbers as given; use the daily lines for detail and the earlier blocks for comparison. Include any training logged today. Sections: what the logs show, wins, gaps in tracking, and one to three practical actions for next week. Do not infer a weight trend from fewer than two weigh-ins. Do not change my calorie or macro targets; if you think they should change, explain why and ask me." |
| Plan a workout | "Help me plan my next workout based on my recent sessions. Base loads on what I actually lifted. Ask for any missing details before making assumptions." |
| Meal idea | "Suggest a meal that fits what's left of today's targets and my dietary preferences. Ask about missing constraints rather than guessing." |

The Overview coach panel has two chips of its own: "What should I focus on?" and "Plan my next workout".

### 8.5 History handling

The client sends at most 12 messages. The server trims again and never trusts the client:
1. Keep items whose `role` is `user` or `assistant` and whose `content` is a non-empty string. Drop everything else, including any `system` role.
2. Keep the last 12.
3. Cap each item at 4000 chars, since a long weekly review can run past 3000.
4. Drop the oldest items until the total is ≤ 24,000 chars (about 6–8K tokens at 3–4 chars per token, with no tokenizer dependency).
5. Drop leading `assistant` items so history starts with `user`.

### 8.6 Reasoning stripping (`ReasoningStripper`)

Applied to `choices[0].message.content`, in order:
1. Remove every `<think>…</think>` block (non-greedy, DOTALL).
2. If a closing `</think>` remains without an opening tag (some chat templates inject the opening tag into the prompt), drop everything up to and including it.
3. If an opening `<think>` remains without a closing tag (truncated output), drop from it to the end.
4. If the text contains gpt-oss channel markup (`<|channel|>final<|message|>`), keep only the text after the last such marker. This handles LM Studio set up not to separate reasoning.
5. Trim.

`reasoning_content` and any other fields are ignored. An empty result → `502 {"error":"The model returned no answer (only reasoning). Try again or shorten the conversation."}`.

### 8.7 Error mapping

| Condition | Status | Message |
|---|---|---|
| Connect refused / unreachable (models call or chat) | 503 | "Can't reach LM Studio at http://127.0.0.1:1234/v1. Start it (or run start.bat) and try again." |
| No chat model loaded (D6) | 503 | "LM Studio is running but no model is loaded. Load one in LM Studio (or run start.bat) and try again." |
| LM Studio HTTP 4xx/5xx | 502 | "LM Studio returned HTTP <code>: <first 300 chars of body>" |
| Timeout (180 s) | 504 | "The model took longer than 3 minutes. Try a shorter question." |
| Malformed response | 502 | "LM Studio returned an unexpected response." |

---

## 9. Frontend

### 9.1 Structure

```
frontend/src/
  main.jsx
  App.jsx               shell: sidebar / bottom bar, hash tab, auth overlay, toast
  api.js                fetch wrapper (+ UnauthorizedError, onUnauthorized hook)
  storage.js            safe JSON get/set/remove for localStorage (try/catch)
  format.js             number/date formatting, todayLocal()
  markdown.js           renderMarkdown() (marked + DOMPurify)
  useLoad.js            per-tab data loading hook
  workoutDraft.js       pure functions for the workout draft (§9.4)
  tabs/
    Overview.jsx
    Workout.jsx         WorkoutEditor + WorkoutHistory
    Nutrition.jsx
    BodyMetrics.jsx
    Coach.jsx
    Goals.jsx
  components/
    LoginOverlay.jsx, WeightChart.jsx, Progress.jsx, Toast.jsx
  style.css
```

Runtime dependencies: `react`, `react-dom`, `marked`, `dompurify`, all with exact versions pinned in `package.json` and bundled by Vite. Dev dependencies: `vite`, `@vitejs/plugin-react`. `vite.config.js` proxies `/api` → `http://127.0.0.1:8765` in dev.

### 9.2 Navigation & state

- Tab ids: `overview | workout | nutrition | body | coach | goals`. The active tab is `location.hash` (`#workout`). A `hashchange` listener updates it. An unknown or empty hash shows `overview`.
- **Only the active tab is rendered.** Each tab loads its own data with one effect on mount (and again when it re-mounts or `authEpoch` changes). There's no global data cache and no refresh-everything call.
- After a mutation, update the local list from the server's response (insert, replace or remove). Don't refetch.
- Data per tab:

| Tab | Calls on mount |
|---|---|
| Overview | `GET /api/dashboard` |
| Workout | `GET /api/workouts` |
| Nutrition | `GET /api/meals` + `GET /api/settings`, in parallel. Today's totals are computed client-side from the list. |
| Body Metrics | `GET /api/weigh-ins` (the chart uses the last 30 days from the list) |
| Coach | `GET /api/health` (status pill only) |
| Goals | `GET /api/settings` |

- Overview chips navigate to `#coach` with the chip text pre-filled in the composer. A `pendingPrompt` value is held in `App` state and consumed by Coach on mount.

### 9.3 Auth handling

- `api.js`: on `401`, call the registered `onUnauthorized()` and throw `UnauthorizedError`.
- `App` keeps `needsLogin`. When it's true, `LoginOverlay` is drawn **on top of** the mounted app. The tab component tree isn't unmounted, so any in-progress form state survives. The workout draft and chat history are in `localStorage` regardless.
- On successful login, `needsLogin = false` and `authEpoch++`, which makes the active tab reload its data. **The failed action isn't retried automatically** (no hidden double submits). The user taps it again, and the draft is still there.
- The first page load just calls the active tab's endpoint. A 401 shows the overlay. No separate session check.
- Logout (in Goals and the sidebar footer): `POST /api/auth/logout`, then show the overlay. `localStorage` isn't touched (I9).

### 9.4 Workout tab and the local draft

**Draft storage:** `localStorage["fitlocal.workoutDraft.v1"]`

```json
{
  "mode": "new",                 // or "edit"
  "workoutId": null,             // set when mode = "edit"
  "submissionId": "3f1c…",       // crypto.randomUUID(), created once per new draft
  "workoutDate": "2026-11-20",
  "notes": "",
  "exercises": [
    { "key": "k1", "name": "Incline DB Press", "notes": "",
      "sets": [ { "key": "s1", "reps": "8", "weightKg": "17.5" } ] }
  ],
  "updatedAt": "2026-11-20T18:05:02"
}
```

- Inputs are kept **as strings**, so a half-typed value such as `17.` survives a refresh. Parsing happens only on submit.
- The draft is written **on every change**. It's a single `useEffect` on the draft object, and synchronous `localStorage` writes are cheap at this size.
- If the stored JSON can't be parsed, move it to `fitlocal.workoutDraft.corrupt`, warn with a toast, and start with no draft. Never silently overwrite it.
- A draft with no exercises and empty notes counts as no draft.

**State machine:**

```
          Start workout                      Finish → 2xx
 [NoDraft] ───────────▶ [Draft:new] ───────────────────────▶ [NoDraft]  (draft cleared)
     │                     │  ▲  Finish → error/offline/401: stay, draft kept, show error
     │                     └──┘
     │ Edit (history row)               Save → 2xx
     └──────────────────▶ [Draft:edit] ─────────────────────▶ [NoDraft]
                           │  Save → 404: offer "Save as new workout" (POST) or "Discard"
                           │  Save → other error: stay, draft kept
     Discard (confirm) from any Draft state ──────────────────▶ [NoDraft]
```

- **One slot.** While a draft exists, the history list's Edit buttons are disabled with the hint "Finish or discard your current workout first". The Delete button for the workout being edited is also disabled.
- Edit loads the server copy (already in the history list) into the draft, with numbers converted to strings.
- `submissionId` is sent only for `mode: "new"`.

**Editor behavior:**
- Session fields: date (defaults to today), notes.
- "Add exercise" appends `{ name: "", notes: "", sets: [ {reps: "", weightKg: ""} ] }` and focuses the name field.
- "Add set" pre-fills `reps` and `weightKg` **from the previous set of that exercise**.
- Remove set and remove exercise. Reordering isn't in phase 1.
- Inputs: `inputmode="numeric"` for reps, `inputmode="decimal"` for weight, steppers aren't required. All targets ≥44 px.
- Before submitting, check client-side that there's ≥1 exercise, every name is non-empty, every exercise has ≥1 set, and reps parse as integers ≥1. Highlight the bad fields. The server checks again (§7.3).
- Finish/Save button states: idle → "Saving…" (disabled) → success toast or inline error.

**History:** a list of sessions (date, exercise count, total sets, first few exercise names). Expanding a row (`<details>`) shows each exercise with its per-set reps @ kg. Actions: Edit, Delete (with confirm).

### 9.5 Nutrition / Body Metrics

- Each tab has a form card, a list, and (Body Metrics) the trend chart.
- **Edit:** tapping Edit on a row loads it into the form card, which shows "Editing <date>" with Save and Cancel buttons. Save sends `PUT`, and the list row is replaced from the response.
- Delete uses `confirm()`, then `DELETE`, and the row is removed.
- Nutrition stat cards show today's calories and macros against targets.
- Body Metrics uses weight plus free-text notes. The placeholder suggests the `BF: 17.5%, MM: 37%` convention. There are no body-fat columns.

### 9.6 Overview, Coach, Goals

- **Overview:** stat cards (calories today, protein today, training days /7, latest weight), macro check-in bars, weekly averages (calories and protein per logged day), the weight trend chart, the coach panel with chips, recent activity, and a short disclaimer that the coach isn't medical advice.
- **Coach:**
  - Chat history is stored in `localStorage["fitlocal.chat.v1"]` as `[{role, content}]`, at most 12 entries. It's rendered on mount.
  - Send: show the user bubble, then a "Thinking…" bubble, `POST /api/coach` with `{message, history}`, then replace the bubble with the rendered answer.
  - On success, append the user/assistant pair and trim to 12. On failure, show the error inside the bubble and **don't** add it to history.
  - Composer: Enter sends, Shift+Enter adds a newline, and the button is disabled while waiting.
  - Chips fill the composer, so the prompt can be edited before sending.
  - "Clear chat" empties the history.
  - The status pill comes from `/api/health`, refreshed on mount and after a failed send.
  - "View context" opens a panel with the system prompt and tracker block from `GET /api/coach/context` (D4), with a refresh button.
- **Goals:** the form for the 4 targets and 4 free-text fields. `PUT /api/settings`, then a success message. Copy notes that these fields are what the coach sees.

### 9.7 `renderMarkdown`

Libraries imported from npm, never from a CDN (C5):

```js
import { marked } from 'marked';
import DOMPurify from 'dompurify';

export function renderMarkdown(text) {
  return DOMPurify.sanitize(marked.parse(text, { breaks: false, gfm: true }));
}
```

- The result is used only through `dangerouslySetInnerHTML` on the message bubbles. User and assistant messages are rendered with the same function.
- Always sanitize the output with DOMPurify, and never render `marked` output on its own. The CSP (§6.1) is a second layer.

### 9.8 Weight chart

A hand-drawn SVG **line chart over the last 30 days** (D3):
- One x slot per day (today and the 29 days before), `MM-DD` labels every 7 days and on today. Days without a weigh-in have no dot; the line connects the days that have one. With several weigh-ins on one day, the last counts.
- The **y-axis is fitted to the data, never forced to 0**: range = min − pad … max + pad, pad = max(15 % of the range, 0.2 kg), with up to 5 "nice" ticks (0.1, 0.2, 0.25, 0.5, 1, … kg steps). Small changes of a few tenths are visible.
- The latest point is larger and labeled with its value; every dot has a `<title>` tooltip.
- Drawn at the container's measured pixel width (ResizeObserver) so text stays readable on phones.
- Caption: `<first date> → <last date>: ±X kg over N weigh-ins. The scale starts near your lowest weight, so small changes look larger. Short-term changes can reflect hydration, food, and other normal fluctuations.` With fewer than 2 points in the window: "Add two or more weigh-ins in the last 30 days to see your trend."
- Used on Overview (from `dashboard.weightLast30`) and Body Metrics (from the list).

### 9.9 Styling & responsiveness

- Dark theme. The `:root` palette: `--bg #0e1114`, `--sidebar #111619`, `--panel #171c20`, `--panel-2 #1b2225`, `--line #2a3235`, `--text #ecf1eb`, `--muted #87918e`, `--green #c5f56b`, `--green-dark #1d3220`, `--orange #ffb26d`, `--blue #8bbbf7`, `--violet #b4a0f5`, `--radius 17px`. Delete rules that end up unused.
- Fonts: the font stack names DM Sans and Manrope but never loads them, so it falls back to `system-ui`. Keep that, and **don't** add Google Fonts (privacy).
- Breakpoints: above 850 px, fixed sidebar. At 850 px or below, sidebar hidden, **fixed bottom tab bar** with 6 items (icon + short label, I2), `padding-bottom: env(safe-area-inset-bottom)`, and the main content padded so the bar never covers it. `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`.
- Tables become stacked cards at 560 px or below.
- A toast shows short success and error messages.

---

## 10. `start.bat`

Windows only, and the single launcher for the app. Always `cd /d "%~dp0"`.

**Usage:**
```
start.bat                         start LM Studio + model, build jar if missing, run app
start.bat --rebuild               same, but always rebuild first
start.bat --create-user <name>    build jar if missing, run subcommand, exit (no LM Studio)
start.bat --create-sample-db      build jar if missing, run subcommand, exit (no LM Studio)
```

**Flow:**
1. **Prerequisites:** `java` on PATH with `java.specification.version` ≥ 21 (parse `java -XshowSettings:properties -version`). Otherwise print the install hint and exit. Node/npm are checked only when a build is needed.
2. **Already running?** For everything except subcommands, check `127.0.0.1:8765` *before* building. If something is listening, say that FitLocal or another app is already using the port and exit 1. The running app holds the jar open, so a rebuild would otherwise fail halfway with a Maven error. Subcommands skip this check and work alongside the running app.
3. **Build** if `backend\target\fitlocal.jar` is missing or `--rebuild` was passed:
   - `cd frontend && npm ci && npm run build`
   - `cd backend && mvnw.cmd -q -DskipTests package` (tests run separately with `mvnw test`)
   - Stop on the first failure.
4. **Subcommand?** If so, run `java -jar backend\target\fitlocal.jar %*` and exit with its exit code. Steps 5–6 are skipped.
5. **LM Studio:**
   - Find `lms.exe` (`%USERPROFILE%\.lmstudio\bin\lms.exe`, then `where lms.exe`). If it's missing, warn and skip to step 6. The tracker works without the coach.
   - Start the LM Studio desktop app if it isn't running (check `tasklist`, try the usual install locations), then wait.
   - **If a chat model is already loaded** (`lms ps --json`, parsed by a one-line PowerShell command: the first entry with `type` `llm` or `vlm`), use it: print its identifier and context length ("unknown" if `lms` doesn't report one), warn if the context is below 16384, and load nothing (D6). The check is a `call :detect_loaded` subroutine, used again after loading. Two models rarely fit in VRAM together.
   - Otherwise read the model key from `fitlocal.local.cmd` (gitignored, `call`ed by the script). On first run, show `lms ls`, prompt for the key, and save `set "MODEL_KEY=…"` plus `set "CONTEXT_LENGTH=20480"`. Then run `lms load "%MODEL_KEY%" --context-length %CONTEXT_LENGTH% --gpu max` (C8), with no custom identifier, so the model keeps its own key as its name.
   - If `curl -fsS http://127.0.0.1:1234/v1/models` fails, `lms server start --port 1234 --bind 127.0.0.1`, then poll for up to 80 s.
   - If `start.bat` loaded a model and the loaded-model check now finds it, `set "LM_STUDIO_MODEL=<the identifier LM Studio reports>"` so the app prefers it while it stays loaded. If a model was already loaded, `LM_STUDIO_MODEL` stays unset and the app follows whatever is loaded.
6. **Run:** `java -jar backend\target\fitlocal.jar` in the foreground, in this window. Print the local URL and a reminder to use the Tailscale HTTPS URL from other devices.

**Notes:**
- The jar isn't rebuilt automatically after a `git pull`. Use `--rebuild` (documented in the README).
- Windows Startup setup goes in the README: a shortcut to `start.bat` in `shell:startup`, after one interactive first run.

---

## 11. Repository hygiene & README

### 11.1 `.gitignore`

```gitignore
# runtime data — personal health data must never be committed
data/*
!data/fitlocal.db.example

# local launcher config
fitlocal.local.cmd

# build outputs
backend/target/
frontend/dist/
frontend/node_modules/

# tooling
.idea/
.vscode/
*.log
```

Additional rules:
- **No fixture, test, screenshot, doc or example may contain real log, allergy or health data.** Tests build synthetic data in temp dirs (§12). A real database is never copied into the repo.
- Before the first public push, run `git ls-files data/` and confirm only `data/fitlocal.db.example` is listed. Also open the example and confirm every table is empty.

### 11.2 README outline

1. **What it is:** a one-paragraph pitch, plus a screenshot taken with synthetic data only.
2. **Architecture:** the §3.1 diagram, the stack, and the main design choices (single jar, SQLite + `schema.sql`, no Flyway, local LLM, the localStorage draft).
3. **Security model:** the §6.5 table, plus the CSRF reasoning (C10).
4. **Setup:** install JDK 21 (+ Node for building) → clone → `start.bat --create-user <name>` → `start.bat` → configure Tailscale Serve to `http://127.0.0.1:8765` → optional Windows Startup shortcut.
5. **Operations:** reset (`copy data\fitlocal.db.example data\fitlocal.db`), log out all devices (delete `data\signing.key`), reset a password (`--create-user` an existing name), rebuild after pulling (`--rebuild`), changing the schema (C14; regenerate the sample DB).
6. **LLM notes:** target models, 20K context, `LM_STUDIO_MODEL` / `LM_STUDIO_BASE_URL`, and how the context is built (link to §8 of this spec).
7. **Development:** `mvnw spring-boot:run` + `npm run dev`, `mvnw test`.

---

## 12. Testing

JUnit 5, `spring-boot-starter-test`, `spring-security-test`, MockMvc. Each test class uses a **temp-file SQLite DB** (`@TempDir` + `@DynamicPropertySource` setting `fitlocal.data-dir`). Not `:memory:`, because each pooled connection to `:memory:` would get its own empty DB. A fixed `Clock` bean is used for time-dependent tests.

**AuthTests**
- Login with correct credentials → 204. The cookie has `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, `Max-Age=2592000`.
- Token expiry is exactly now + 30 days. With the clock moved +30 days − 1 s, the token is accepted. At +30 days + 1 s, it's rejected (401).
- Wrong password, or unknown user → 401, with no `Set-Cookie`.
- `GET /api/meals` with no cookie → 401 JSON. With a tampered signature, payload username, or expiry → 401. With a garbage cookie → 401.
- After a password reset, the old token → 401.
- `GET /` and `POST /api/auth/login` work without a cookie.
- The signing key is created on first start and reused on the second, so a token survives a context restart.

**WorkoutApiTests**
- POST with 2 exercises and varying per-set reps/weights → 201. A GET round-trip gives the same structure and order.
- Validation: no exercises, blank name, reps 0, weight −1 → 400 with a field-specific message.
- PUT replaces everything. Afterwards the DB has **only** the new exercise and set rows (count queries). `id` and `createdAt` are unchanged. PUT to an unknown id → 404.
- DELETE cascades to exercises and sets (count queries) → 204. A second DELETE → 404.
- POST twice with the same `submissionId` → 201 then 200, and one workout in the DB.

**SampleDbTests** (C15)
- Apply `schema.sql` to a fresh temp DB. Its `sqlite_master` (type, name, normalized sql) equals the committed `data/fitlocal.db.example`, and every table in the example has 0 rows.

**Coach**
- `CoachContextBuilderTest`: fixed clock and fixture data → assert the exact tracker block. Covers the "Now:" line, the today-in-progress section (including just after midnight with nothing logged), the "of target" / under/over wording, every empty-case line, the no-added-weight format, and the one-weigh-in line. Reading the context correctly is the main quality requirement, so it's worth one golden-text test.
- `ReasoningStripperTest`: the cases in §8.6.
- `CoachStatsTest`: the 10% calorie band edges, protein met at equality, averages over logged days only, the 7-day blocks (D5).
- `CoachHistoryTest`: untrusted history items dropped, last 12 kept, per-item and total caps, starts with a user turn (§8.5).
- `ModelPickerTest` and `LmStudioClientTest`: model selection from what is loaded (D6). The client test runs the real client against a fake LM Studio on the JDK's built-in HTTP server: loaded vs. downloaded models, configured-model preference, no model loaded (503, no chat request), the fallback for LM Studio versions without the REST API, and an unreachable server.

**Other APIs**
- `MealAndWeighInApiTests`, `SettingsApiTests` (in-code defaults, canonical stored numbers), `DashboardApiTests` (window edges), `SigningKeyStoreTest` (key reused across restarts, malformed key file stops startup).

Frontend: no test framework in phase 1. If one is added later, start with the workout draft logic (`workoutDraft.js`).

---

## 13. Decision index

All decisions are described in §1. Dates are when each was settled.

| # | Topic | Decision | Date |
|---|---|---|---|
| C1 | Framework version | Spring Boot 4.x (latest patch), JDK 21 | 2026-11-20 |
| C2 | Database | SQLite only, native types | 2026-11-20 |
| C3 | Settings columns | `key` / `value` | 2026-11-20 |
| C4 | Settings defaults | In code, never seeded in the DB | 2026-11-20 |
| C5 | Markdown rendering | `marked` + `dompurify`, bundled | 2026-11-20 |
| C6 | Weekly review | One chip through `/api/coach`, temperature 0.55 | 2026-11-20 |
| C7 | Retry-safe submit | `workouts.submission_id` | 2026-11-20 |
| C8–C17 | Risks | Fixes as written in §1B | 2026-11-20 |
| I1–I9 | Interpretations | As written in §1C | 2026-11-20 |
| D1, D2 | 30-day coach window | Superseded by D5 | 2026-11-21 |
| D3 | Weight chart | 30-day SVG line chart, fitted y-axis | 2026-11-20 |
| D4 | Today in the coach context | Current time given; today in progress, kept out of counts; View context panel | 2026-11-21 |
| D5 | Coach context layout | 28 days as four 7-day blocks; AT A GLANCE computed by the app; oldest first, dated lines | 2026-11-21 |
| D6 | Model selection | Only a loaded model; `start.bat` loads one only when none is loaded | 2026-11-21 |

---

## 14. Acceptance checklist

- [ ] `start.bat` on a fresh clone builds the jar, starts LM Studio, loads the chosen model with a 20K context, or uses the model already loaded in LM Studio instead of loading a second one (D6), and serves the app on `127.0.0.1:8765` only (`netstat` shows no `0.0.0.0:8765`).
- [ ] The app works through the Tailscale Serve URL (`tailscale serve --bg 8765`).
- [ ] `--create-user` and `--create-sample-db` run without starting LM Studio or binding a port.
- [ ] Every `/api/**` endpoint except login returns 401 without a valid cookie. JS can't see the auth cookie (`document.cookie` doesn't contain it).
- [ ] A workout draft survives refresh, closing the browser, airplane mode during Finish, and a 401 during Finish. It's cleared only after a 2xx.
- [ ] Edit → Save replaces the sets. Editing is blocked while another draft exists.
- [ ] A coach reply contains no `<think>` text. The tracker block for an empty week states "no … logged" for each category.
- [ ] The browser makes no third-party network requests (check the DevTools Network tab).
- [ ] On a 375 px-wide phone: bottom tab bar, all targets ≥44 px, no horizontal scroll, no zoom on input focus.
- [ ] `mvnw test` passes.
- [ ] `git ls-files` shows no personal data. `data/` contains only the empty example.

---

## 15. Suggested build order

1. **Skeleton:** Spring Initializr project (Web, Security, Data JPA) + `sqlite-jdbc` + `hibernate-community-dialects`. `schema.sql`, converters, properties. Get the app booting against an empty `data/`.
2. **CLI:** dispatch, `--create-sample-db` + the drift test, `--create-user`.
3. **Auth:** key store, token service, filter, login/logout, AuthTests.
4. **CRUD:** meals, weigh-ins, settings, workouts (+ WorkoutApiTests), dashboard.
5. **Coach:** context builder (+ golden test), stats, LM Studio client, model picker, stripper, health.
6. **Frontend:** shell + auth overlay + CSS → Nutrition/Body/Goals → Workout editor and draft → Overview → Coach.
7. **Packaging:** `start.bat`, `.gitignore`, README, final privacy check, first public push.
