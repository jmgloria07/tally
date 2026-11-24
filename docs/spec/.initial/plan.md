# FitLocal Phase 1: Implementation Plan

| | |
|---|---|
| **Source** | [spec.md](spec.md) (normative), [intent.md](intent.md) (goals) |
| **Date** | 2026-11-21 |
| **Rule** | Where this plan and spec.md differ, spec.md wins. Section refs (§) point to spec.md. |

This plan turns spec §15 ("Suggested build order") into milestones you can check off. Each milestone ends with a **Done when** gate that can be verified. Don't start a milestone until the previous gate passes. The frontend (M6) is the exception: it only needs the API from M4/M5.

One change to the spec's order: `.gitignore` moves to **M0**, before any file appears in `data/`. Personal data then can't be staged by accident, even before the first push.

---

## Milestone overview

| # | Milestone | Main output | Tests added |
|---|---|---|---|
| M0 | Repo hygiene | `.gitignore`, folder layout | — |
| M1 | Backend skeleton | Boot 4 app boots on SQLite with `schema.sql` | Context-loads smoke test |
| M2 | CLI + sample DB | Subcommand dispatch, `--create-sample-db`, `--create-user` | SampleDbTests |
| M3 | Authentication | Signing key, token, filter, login/logout | AuthTests, SigningKeyStoreTest |
| M4 | Data API | Meals, weigh-ins, settings, workouts, dashboard | WorkoutApiTests, MealAndWeighInApiTests, SettingsApiTests, DashboardApiTests |
| M5 | Coach | Context builder, stats, LM Studio client, model picker, stripper, health | CoachContextBuilderTest, CoachStatsTest, CoachHistoryTest, ReasoningStripperTest, ModelPickerTest, LmStudioClientTest |
| M6 | Frontend | React SPA, all six tabs | — (manual checks) |
| M7 | Packaging & release | `start.bat`, README, privacy check, first push | Full acceptance checklist (§14) |

---

## M0 — Repo hygiene

- [ ] Create `.gitignore` exactly as in §11.1.
- [ ] Create empty `backend/`, `frontend/`, `data/` folders (layout in §3.2).
- [ ] Commit `intent.md`, `spec.md`, `plan.md`, `.gitignore`.

**Done when:** `git status` is clean, and creating a dummy `data/test.db` doesn't show up as untracked.

---

## M1 — Backend skeleton (§3, §4)

- [ ] Generate the project with Spring Initializr: Boot **4.x** latest patch, JDK 21, Maven, group `app.fitlocal`. Starters: Web MVC, Security, Data JPA, plus the matching test starters (C1).
- [ ] Add `org.xerial:sqlite-jdbc` and `org.hibernate.orm:hibernate-community-dialects`. Leave the dialects version unset so Boot's BOM manages it.
- [ ] `pom.xml`: `<finalName>fitlocal</finalName>`. Add `../frontend/dist` as an extra resource dir with `targetPath=static` (§3.3). Check that a missing `dist/` doesn't break the build.
- [ ] `application.properties` exactly as in §3.4.
- [ ] `schema.sql` exactly as in §4.2.
- [ ] Create the data dir on startup if it's missing, before the DataSource initializes (`DataDirInitializer`).
- [ ] `common/`: `IsoDateConverter`, `IsoDateTimeConverter` (`autoApply = true`, C13), `ClockConfig` (`Clock` bean with `ZoneId.systemDefault()`, I3).
- [ ] Entities + repositories: `User`, `Workout`/`WorkoutExercise`/`WorkoutSet` (cascade, orphan removal, `@OrderBy`), `Meal`, `WeighIn`, `Setting` (§4.3).
- [ ] Shared test base: `@TempDir` + `@DynamicPropertySource` for `fitlocal.data-dir`, fixed `Clock` override (§12).

**Done when:** `mvnw test` passes a context-loads test against a temp DB, and running the app creates `data/fitlocal.db` with all 7 tables. Spot-check that a saved `Meal` stores `meal_date` as ISO text (not epoch ms) and that `Setting` reads and writes `key`/`value` without quoting errors.

**Risks to check here:** the Boot 4 starter names; whether Hibernate 7's `SQLiteDialect` handles `IDENTITY` on `INTEGER PRIMARY KEY`; and whether `key`/`value` need quoting (fallback in §4.3).

---

## M2 — CLI dispatch & sample DB (§5.1, §5.2, §4.4)

- [ ] `FitLocalApplication.main`: recognize `--create-user` and `--create-sample-db`. Run them with `WebApplicationType.NONE`, then `System.exit(code)`. An unknown `--flag` prints usage and exits 1.
- [ ] `SampleDbCommand`: delete and recreate `data/fitlocal.db.example`, apply `schema.sql` through `ScriptUtils` on a raw connection, exit 0.
- [ ] Generate and commit `data/fitlocal.db.example`.
- [ ] **SampleDbTests** (C15): fresh temp DB from `schema.sql` vs. the committed example. Compare normalized `sqlite_master` (type, name, sql) and assert 0 rows in every table.
- [ ] `CreateUserCommand`: username rules, `System.console()` null check, password read twice (min 8), BCrypt insert, reset prompt for an existing user (§5.2).

**Done when:** both flags run with no port bound. Check with `netstat` while `--create-user` waits at the prompt. SampleDbTests passes. Editing `schema.sql` without regenerating makes it fail (try it once, then revert).

---

## M3 — Authentication (§6)

- [ ] `SigningKeyStore`: 32 random bytes, base64, `data/signing.key`. Write to a temp file and move it atomically. A malformed file fails startup.
- [ ] `AuthTokenService`: the `v1|username|expiresAt` payload; HMAC-SHA256 over `payload|passwordHash`; 30-day expiry from `Clock`; constant-time verify (§6.2).
- [ ] `AuthCookieFilter` for `/api/**`, steps 1–6 in §6.3.
- [ ] `SecurityConfig`: stateless; CSRF, formLogin, httpBasic and logout DSLs off; permit login and static files; custom JSON 401 entry point; CSP header (§6.1).
- [ ] `AuthController` + `AuthService`: login (dummy BCrypt compare for unknown users, `ResponseCookie`), logout (`Max-Age=0`) (§6.4).
- [ ] `ApiErrorHandler` + `Checks` helper: the `{"error": ...}` shape and status mapping (§7.1). Built here because auth is the first thing that needs it.
- [ ] **AuthTests** and **SigningKeyStoreTest**: every case in §12.

**Done when:** the tests pass, including the ±1 s expiry boundary, tamper cases, password-reset revocation, and the key surviving a context restart.

---


## M4 — Data API (§7)

Build in this order, simplest first:

- [ ] **Settings:** `SettingsService` merges stored rows over in-code defaults (§4.3); canonical number text; `GET`/`PUT /api/settings`.
- [ ] **Meals:** DTO record with compact-constructor checks (I6, §7.3); list (`limit` clamped 1..500, newest first), create, update, delete.
- [ ] **Weigh-ins:** same pattern as meals.
- [ ] **Workouts** (`WorkoutService`):
  - [ ] `GET` the 100 most recent, in full detail (batch fetch, no N+1).
  - [ ] `POST`: positions and set numbers from array order; idempotent on `submissionId` (existing → `200`, nothing inserted) (C7).
  - [ ] `PUT`: load → set fields → `exercises.clear()` → **`flush()`** → append → save. `id`/`createdAt`/`submissionId` unchanged. Unknown id → 404.
  - [ ] `DELETE` cascades. Missing → 404.
- [ ] **Dashboard** (`DashboardService`): one call that returns everything in the §7.4 shape. Today/7-day window from `Clock`, upper bound = today (I3); averages per logged day; `weightLast30`; `recentActivity` up to 7 items.
- [ ] Request body limit 1 MB; `consumes = APPLICATION_JSON` on mutating endpoints (→ 415).
- [ ] Request logging (`RequestLoggingFilter`): method, path, status, duration only. No bodies.
- [ ] **WorkoutApiTests**: every case in §12. Add happy-path and validation tests for meals, weigh-ins and settings, and a dashboard test with a fixed clock and fixture data.

**Done when:** all tests pass. A quick manual pass with curl + cookie against a DB with some synthetic data returns sensible dashboard numbers.

---

## M5 — Coach (§8)

- [ ] `CoachPrompts`: `COACH_RULES`, `DATA_READING_RULES`, `FORMATTING_RULES` with the content listed in §8.4; the three chip prompts.
- [ ] `CoachStats`: every AT A GLANCE number in §8.4 (D5).
- [ ] `CoachContextBuilder.build(now)` in a `@Transactional(readOnly = true)` that **ends before** the LLM call (C12). Returns `systemPrompt` + `trackerBlock` in the normative format (§8.4): the `Now:` line, four 7-day blocks, today in progress, number formatting, under/over wording, every empty-case line, the no-added-weight format, size caps.
- [ ] `CoachHistory`: trimming steps 1–5 in §8.5.
- [ ] `ReasoningStripper`: the cases in §8.6.
- [ ] `ModelPicker` + `LmStudioClient`: shared `HttpClient`; loaded-model resolution through `/api/v0/models` with the `/v1/models` fallback (§8.2, D6); temperature 0.55, no `max_tokens`, `stream: false`; error mapping in §8.7.
- [ ] `CoachService` + `CoachController`: `POST /api/coach`, `GET /api/coach/context` (D4), and `GET /api/health` (2 s timeout, never errors).
- [ ] **Tests**: CoachContextBuilderTest (golden text), CoachStatsTest, CoachHistoryTest, ReasoningStripperTest, ModelPickerTest, LmStudioClientTest (fake LM Studio on the JDK HTTP server).

**Done when:** tests pass. With LM Studio running gpt-oss-20B and then Qwen3 14B at 20K context, a "Weekly review" against real data returns a clean answer with no `<think>` text that quotes the glance numbers correctly. An empty DB produces "no … logged" for every category. With no model loaded, the coach returns the 503 message and LM Studio loads nothing.

---

## M6 — Frontend (§9)

- [ ] **Scaffold:** Vite + React; runtime deps `react`, `react-dom`, `marked`, `dompurify` at exact pinned versions; `/api` proxy; viewport meta (§9.9).
- [ ] **Core modules:** `api.js` (401 → `onUnauthorized` + `UnauthorizedError`), `storage.js`, `format.js`, `markdown.js` (§9.7), `useLoad.js`.
- [ ] **Shell:** `App.jsx` with hash tabs, only the active tab rendered, `needsLogin` overlay on top of the mounted tree, `authEpoch`, toast, `pendingPrompt` (§9.2–9.3).
- [ ] **CSS:** the §9.9 palette, no web fonts; sidebar above 850 px, bottom bar at 850 px or below; stacked tables at 560 px or below; ≥44 px targets, 16 px inputs.
- [ ] **Nutrition, Body Metrics, Goals:** form card + list + inline edit/delete; `WeightChart` (§9.5, §9.8); logout button.
- [ ] **Workout:** draft logic in `workoutDraft.js`; draft in `localStorage["fitlocal.workoutDraft.v1"]` with string inputs, a write on every change, corrupt-draft quarantine; the state machine in §9.4 including the 404 "Save as new / Discard" branch; single draft slot blocks Edit; history with `<details>`.
- [ ] **Overview:** stat cards, macro bars, both weekly averages, chart, coach chips → `#coach`, recent activity, disclaimer (§9.6).
- [ ] **Coach:** `localStorage["fitlocal.chat.v1"]` (≤12); Thinking bubble; failures not added to history; Enter / Shift+Enter; chips fill the composer; Clear chat; health pill; View context panel.

**Done when:** every tab works against the dev backend on a desktop browser and on a 375 px viewport. The workout draft survives a refresh, the backend being stopped during Finish, and a 401 during Finish (delete `signing.key` and restart). DevTools Network shows no third-party requests.

---

## M7 — Packaging & release (§10, §11)

- [ ] `start.bat` following §10: Java ≥ 21 check; port-in-use check before building; build when the jar is missing or `--rebuild` is passed; subcommand passthrough skips LM Studio; LM Studio discovery and start; **use an already-loaded chat model if there is one (`lms ps --json`)**; otherwise the `fitlocal.local.cmd` first-run prompt and `lms load <key> --context-length 20480 --gpu max`; API start and poll; `LM_STUDIO_MODEL` set only when `start.bat` loaded the model; run in the foreground.
- [ ] README with the 7 sections in §11.2. Screenshot taken with synthetic data only.
- [ ] End-to-end run from a fresh clone, with nothing else on port 8765.
- [ ] Privacy check: `git ls-files data/` lists only the example DB; open the example and confirm it's empty; grep the repo for any real food, exercise or notes strings from your own logs.
- [ ] Go through **every item in §14**.
- [ ] First public push (only after you approve it).

**Done when:** every §14 box is checked.

---

## Cross-cutting rules (apply in every milestone)

- **Privacy:** no real health data in code, tests, fixtures, logs, screenshots or commits. Never log bodies, prompts, replies, passwords or tokens.
- **Scope:** nothing from the spec §2 "Out of scope" list. If something seems to need a new dependency, stop and ask first.
- **Stable references:** code comments cite spec sections and decision IDs. When a design change moves or renames one, update the comments in the same change.
- **Commits:** one or more commits per milestone, each leaving `mvnw test` green.

## Open verification items

These are factual checks the spec flags but couldn't settle in advance. Settle each one in the milestone shown.

| Item | Spec ref | Milestone |
|---|---|---|
| Boot 4 starter and test-starter names in Initializr | C1 | M1 |
| `hibernate-community-dialects` SQLite dialect on Hibernate 7: IDENTITY + `key`/`value` columns | C2, §4.3 | M1 |
| xerial binds `LocalDate`/`LocalDateTime` as text through the converters | C13 | M1 |
| LM Studio's `/api/v0/models` reports `state` and `type` in the installed version; the fallback works without it | D6, §8.2 | M5 |
| `lms ps --json` output shape in the installed `lms` version | D6, §10 | M7 |
| `Secure` cookie survives plain-HTTP localhost in the browsers you use | C11 | M3 / M6 |
