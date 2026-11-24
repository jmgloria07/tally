# Tally (formerly FitLocal)

Self-hosted, single-user fitness tracker with a local-LLM coach. Spring Boot 4 / JDK 21 / SQLite backend, React + Vite frontend, packaged as one jar bound to `127.0.0.1:8765` and reached over Tailscale. Runs on Windows. [README.md](README.md) is the user-facing reference (setup, security model, Discord format, operations, LLM notes); read the relevant section there before changing behaviour it describes, and update it when that behaviour changes.

## Commands

```
cd backend && ./mvnw test                     # all backend tests; no Node needed
cd backend && ./mvnw spring-boot:run          # API on 127.0.0.1:8765 (set TALLY_SECRETS_FILE=../secrets.properties to load secrets)
cd frontend && npm install && npm run dev     # UI on localhost:5173, proxies /api
start.bat --rebuild                           # full build (npm ci + vite build + mvnw package -DskipTests) and run
start.bat --create-sample-db                  # regenerate data/tally.db.example after any schema.sql change
```

`start.bat` refuses to build while the app is running: the running process locks `backend/target/tally.jar`, and so does a running sandbox.

## Spec-driven workflow

Every feature starts as documents, before code, in `docs/spec/YYYYMMDD-<feature>/`:

- `intent.md` (why/what) → `spec.md` (normative, decisions with IDs and reasons) → `plan.md` (milestones, each with a checkable **Done when** gate) → `post-build.md` (differences from spec, additions, verification, incidents).
- `spec.md` wins any conflict. A feature spec lists what it **Amends** in an earlier spec; `docs/spec/.initial/` is the phase 1 baseline (decision IDs like `C1`, `D6`, sections like `§8.4`).
- Each feature has its own decision prefix (`R` rest-client, `E` discord-ingest, `S` tally-sandbox). Code comments cite them: `// see E4`, `(spec §7.3)`. Keep citing them in new code.
- Once a spec is **Approved**, record build-time differences in `post-build.md` and ask the user before editing the spec itself.
- One milestone = one branch (`feature/<feature>`, or `feature/<feature>-m<N>-<slug>` for multi-milestone features) = one PR. Commit prefixes: `feature:`, `housekeeping:`, `chore:`. Push only after the user has reviewed and accepted.

## Backend layout (`backend/src/main/java/app/tally/`)

Package per feature: `meal`, `weighin`, `workout`, `settings`, `dashboard`, `coach`, `ingest` (+ `ingest/discord`), `auth`, `cli`, `common`. A CRUD feature follows the `weighin` shape:

- **Entity** + Spring Data **Repository**.
- **`XDto`**: a final holder class with `Request`/`Response` records. `Request` validates in its compact constructor via `common.Checks`, throwing `ValidationException`; `ApiErrorHandler` unwraps it from Jackson's exception so the field message reaches the client. `Response.from(entity)` formats dates with `IsoFormats`.
- **Service** owns the transactions and logic; throws `NotFoundException`.
- **Controller** is thin. Every mutating endpoint declares `consumes = APPLICATION_JSON_VALUE`: that is half the CSRF defence (README "Why there is no CSRF token").
- Errors are always `{"error": "..."}` from `ApiErrorHandler`. Unexpected errors return a generic message; details go to the server log only.

Cross-cutting rules:

- **Time:** inject `java.time.Clock` and use `ClockConfig.today(clock)` / `ClockConfig.now(clock)` (truncated to seconds). "Today" is the server's local date.
- **Schema:** `schema.sql` is `CREATE TABLE IF NOT EXISTS` only, applied every startup; Hibernate `ddl-auto=none`. Dates are ISO text. Adding a column needs a hand-written `ALTER TABLE` for existing DBs (README "Changing the schema"), then `start.bat --create-sample-db`; `SampleDbTests` fails if `data/tally.db.example` drifts. Add new tables to the cleanup list in `IntegrationTest.resetState()`.
- **Coach:** the app computes every number; the model only reads labeled plain text built by `CoachContextBuilder` (format pinned by `CoachContextBuilderTest`, spec §8.4). `ModelPicker` uses whatever LM Studio has loaded; the app never loads a model.
- **Discord:** `DiscordPort` is the only boundary; Reactor/Discord4J types stay inside `ingest/discord` (`Discord4jPort`). Tests use `FakeDiscordPort`. The `reactor-extra` 3.6.0 pin in `pom.xml` is load-bearing (see its comment); verify Discord changes and Discord4J/Reactor/Netty/Boot version bumps with the `tally-sandbox` skill.
- **CLI:** `--create-user` and `--create-sample-db` run as `CliCommand` beans with no web server (`TallyApplication`).

## Tests

- `*Tests` classes extend `IntegrationTest`: full Spring context, own temp SQLite file under `target/test-data`, `MutableClock` pinned to `DEFAULT_NOW`, Discord token forced blank, `signIn()` returns the auth cookie. `*Test` classes are plain unit tests.
- Pass test-only settings via `@DynamicPropertySource` or command-line args. A Spring "default property" ranks below `application.properties` and once made tests write into `backend/data/`.
- Fixtures use synthetic data only.

## Frontend (`frontend/src/`)

Plain React, no router, state library or chart library: keep it dependency-light (only `marked` + `DOMPurify`). `App.jsx` switches between `tabs/`. All calls go through `api.js` (JSON bodies, 401 → login overlay without discarding local state). `localStorage` goes through `storage.js`, which never throws. The in-progress workout (`workoutDraft.js`) and coach chat live in the browser; a draft is cleared only after the server confirms, and its `submissionId` makes retries idempotent. Model output is rendered via `markdown.js` (sanitized).

## Data and secrets guardrails

- `data/tally.db` is the user's real health data. Never open, write or reset it while developing; use the `tally-sandbox` skill (`data/sandbox`, port 8766, `http://localhost:8766`). Only `data/tally.db.example` is committed.
- Secrets live only in the gitignored root `secrets.properties` (template: `secrets.properties.example`). Check its keys by length, never print values. Never log tokens or Discord message text.
- Env vars differ per shell: `set X=...` (cmd) does nothing in PowerShell (`$env:X = "..."`). A wrong form once made `--create-user` write to the real DB, so give per-shell commands when it matters.
