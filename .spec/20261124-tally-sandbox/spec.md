# Tally Sandbox: Requirements & Design Spec

| | |
|---|---|
| **Status** | Approved (2026-11-24). Written after the build, from the plan approved on 2026-11-23. |
| **Source** | [intent.md](intent.md) |
| **Plan** | [plan.md](plan.md) |
| **Amends** | [`../20261122-discord-ingest/`](../20261122-discord-ingest/): the acceptance skill named in post-build.md §2 is replaced by `tally-sandbox`. No normative app behavior changes. |
| **Date** | 2026-11-24 |

This is the normative design for the `tally-sandbox` Claude Code skill (`.claude/skills/tally-sandbox/`). **Where this spec and intent.md disagree, this spec wins.** It changes no app code, so the phase 1 and discord-ingest specs stay in force unchanged.

Decisions use the prefix **S** (for "sandbox").

---

## Contents

1. [Design decisions](#1-design-decisions)
2. [Scope](#2-scope)
3. [Skill modes](#3-skill-modes)
4. [Helpers](#4-helpers)
5. [Decision index](#5-decision-index)
6. [Acceptance checklist](#6-acceptance-checklist)

---

## 1. Design decisions

**S1. One skill, `tally-sandbox`, replacing `discord-acceptance`.**
- The folder is renamed with `git mv`. Its helpers move with it.
- **Why one skill and not two:** Discord ingestion is accepted, so a separate acceptance skill would mostly sit unused, while its setup steps are exactly what a demo needs. A slim Discord check (S7) covers the remaining acceptance use.

**S2. Its own data folder and port: `data/sandbox` on 8766.**
- Tally starts with `TALLY_DATA_DIR=data/sandbox` and `--server.port=8766`. Both settings already exist, so no app change is needed.
- The real Tally on 8765 can keep running.
- **Jar lock:** a running Tally locks `backend/target/tally.jar`, so `mvnw package` can't replace it. In that case the skill runs the existing jar and says so.
- **Browser address:** `http://localhost:8766`, not `127.0.0.1:8766`. Cookies are per host name, not per port, so a login at `127.0.0.1:8766` would replace the real Tally's session cookie on `127.0.0.1`.

**S3. The sandbox's Discord channel comes only from `sandbox.discord.channel-id`.**
- A skill-only key in `secrets.properties`. The app never reads it; Spring ignores unknown keys.
- The skill passes it as `--tally.ingest.discord.channel-id=<value>`. Command-line arguments outrank the secrets file, so `secrets.properties` never needs editing between runs.
- **When the key is blank:** the sandbox starts with `--tally.ingest.discord.token=` (Discord off). It never falls back to the real `channel-id`.
- **Why:** reactions are the record of what's processed (discord-ingest E1). A sandbox reading the real channel would mark real messages ✅, and the real Tally would then skip them.
- Both instances may share the bot token, since each reads only its own channel.
- The key is listed, blank, in `secrets.properties.example` (discord-ingest E12), under its own heading that says the app doesn't read it.

**S4. Data is kept until a reset.**
- Stop leaves `data/sandbox` in place, and Start reuses it.
- Reset deletes `data/sandbox` and starts again.

**S5. Optional synthetic demo data (`SeedDemoData.java`).**
- On request only, after the first start.
- About 21 days ending today: meals with macros most days, three workouts a week alternating two sessions with weights rising every other session, and weigh-ins every 2–3 days trending down about 1 kg.
- A fixed random seed, so every seeded sandbox looks the same.
- It refuses a database that already has meals or workouts, so it can't double up.
- It leaves `settings` alone, because defaults live in code (phase 1 C4).
- **Why tooling, not an app feature:** phase 1 keeps "demo seed data" out of the app. This runs only from the skill, against the sandbox.

**S6. A fixed test login.**
- `CreateTestUser.java` creates `tester` / `testtest`, or resets that password if the user exists.
- It writes the same BCrypt hash and timestamp format as `AuthService.createUser`, because `start.bat --create-user` reads the password from the console and can't be scripted.
- **Why a fixed password is acceptable:** the sandbox listens on 127.0.0.1 only and is never exposed through Tailscale.

**S7. A slim live Discord check.**
- **When:** after changing ingestion code, or after bumping, pinning or unpinning Discord4J, Reactor, Netty or Spring Boot. A broken gateway login doesn't show up in the automated tests, which use a fake Discord (the `reactor-extra` incident in discord-ingest post-build §2).
- **Steps:** one valid log, one invalid line and `help` while running; one message while stopped; then a restart with nothing posted. Then check the reactions, replies, database counts and ledger, and confirm the log contains neither the token nor any message text.
- The result is reported in chat. Nothing is written to a spec folder.
- **Replaces:** the full discord-ingest §12 checklist run, which isn't repeated.

**S8. Guards in every helper.**
- `CreateTestUser` and `SeedDemoData` write only to a database whose path contains `sandbox`.
- `DbQuery` is read-only, and refuses the real `data/tally.db`.
- `ChannelCheck` reads `sandbox.discord.channel-id` by default, so it checks the same channel the sandbox reads.
- Secret values are checked by length only (`set (N chars)` or `MISSING`) and never printed.

---

## 2. Scope

### In scope
The skill folder (`SKILL.md` and four helpers), the new key in `secrets.properties.example`, the README's sandbox tip, and one line in discord-ingest post-build.md recording the rename.

### Out of scope (do not build)
Everything listed in [intent.md](intent.md) under "Explicitly not in this change". Also any change to app code, `schema.sql`, `application.properties` or `start.bat`.

---

## 3. Skill modes

| Mode | Trigger | Result |
|---|---|---|
| Start (default) | Demo, recording, screenshots, trying something | Sandbox running on `http://localhost:8766` with the test login. Discord is on only if S3's key is set. |
| Seed | Asked for demo or fake data | S5 data in an empty sandbox |
| Stop | Done recording | Port 8766 free, data kept (S4) |
| Reset | Wipe or reseed | Empty sandbox, restarted |
| Discord check | Ingestion code or dependency change | S7 report in chat |

The skill also keeps the startup-failure diagnosis from the acceptance skill (version skew between Spring Boot and Discord4J) and its gotchas, including the PowerShell syntax for environment variables.

---

## 4. Helpers

All run as single-file Java programs on the backend classpath (`java -cp "$CP" <file>.java …`), because `python` can hang on stdin here and `sqlite3` isn't installed.

| File | Purpose | Guard (S8) |
|---|---|---|
| `CreateTestUser.java` | The `tester` login (S6) | Path must contain `sandbox` |
| `SeedDemoData.java` | Demo data (S5) | Path must contain `sandbox`; refuses a database that already has logs |
| `DbQuery.java` | One `SELECT`, tab-separated | Read-only; refuses the real `data/tally.db` |
| `ChannelCheck.java` | The test channel's recent messages with the bot's reactions and replies | Reads `sandbox.discord.channel-id` unless another key is given; never prints the token |

If the user schema or the password encoder changes, `CreateTestUser.java` and `SeedDemoData.java` must change with it.

---

## 5. Decision index

| ID | Decision |
|---|---|
| S1 | One skill, `tally-sandbox`, replacing `discord-acceptance` |
| S2 | `data/sandbox` on port 8766, opened at localhost |
| S3 | Discord only from `sandbox.discord.channel-id`; blank means Discord off |
| S4 | Data kept until a reset |
| S5 | Optional synthetic demo data |
| S6 | Fixed `tester` login |
| S7 | Slim live Discord check |
| S8 | Guards in every helper |

---

## 6. Acceptance checklist

- [ ] The sandbox starts on 8766 while the real Tally runs on 8765, and the real `data/tally.db` is never opened.
- [ ] With the S3 key blank, the log shows Discord ingestion is off.
- [ ] `tester` / `testtest` signs in at `http://localhost:8766`.
- [ ] Seeding fills the dashboard (weight chart, weekly averages, recent activity). A second seed is refused.
- [ ] The seed and query helpers refuse the real `data/tally.db`.
- [ ] With the S3 key set, the sandbox connects to the test channel only, and nothing in the real channel gets a reaction.
- [ ] Stop keeps `data/sandbox`. Reset empties it.
- [ ] Signing into both Tallys in one browser doesn't sign either out.
- [ ] The S7 Discord check passes end to end.
- [ ] The skill is used for demo, sandbox and live-Discord-check requests, and not for close calls (trigger eval).
