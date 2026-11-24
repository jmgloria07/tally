# Lean start: Requirements & Design Spec

| | |
|---|---|
| **Status** | Approved |
| **Source** | [intent.md](intent.md) |
| **Plan** | [plan.md](plan.md) |
| **Amends** | [`../.initial/spec.md`](../.initial/spec.md) §10 (`start.bat` usage and step 5), D6 (who loads a model), §8.7 (two error messages), §14 acceptance item on `start.bat` |
| **Date** | 2026-11-24 |

This is the normative design for making LM Studio opt-in in `start.bat`. **Where this spec and intent.md disagree, this spec wins.** Where it disagrees with the phase 1 spec, this spec wins only on the points listed under **Amends**.

Decisions use the prefix **L** (for "lean start"). Code comments cite them as in phase 1, e.g. `rem see L1`.

---

## Contents

1. [Design decisions](#1-design-decisions)
2. [Scope](#2-scope)
3. [Testing](#3-testing)
4. [Decision index](#4-decision-index)
5. [Acceptance checklist](#5-acceptance-checklist)

---

## 1. Design decisions

**L1. Plain `start.bat` skips LM Studio. `--coach` opts in.**
- Without `--coach`, `start.bat` runs no LM Studio step at all: no `lms` lookup, no `tasklist`, no `curl` probe, no `lms ps`. After the build step it prints one hint line and runs Tally:
  `AI coach: start LM Studio and load a model when you need it, or run start.bat --coach.`
- With `--coach`, phase 1 §10 step 5 runs exactly as today: find `lms.exe`, start the desktop app if needed, use an already-loaded chat model or load the configured one, start the API on `127.0.0.1:1234` and wait for it.
- **Why:** the coach is rarely used, and step 5 costs startup time (up to ~90 s of waits) plus VRAM/RAM for a model that sits idle. The app already handles LM Studio being absent or started later (D6, health pill), so nothing else needs to change.
- **Why not delete step 5:** it encodes hard-won behaviour (no second model load, context warning, localhost-only API bind, `LM_STUDIO_MODEL` preference). An unused flag costs nothing at startup.
- **Why no probe in the default path:** the Coach tab's status pill already reports live status. Probing would add latency and output for no decision.

**L2. Argument rules.**

| Command | Result |
|---|---|
| `start.bat` | build if the jar is missing, run Tally (no LM Studio) |
| `start.bat --rebuild` | always rebuild, run Tally (no LM Studio) |
| `start.bat --coach` | build if missing, LM Studio step, run Tally |
| `start.bat --rebuild --coach` or `--coach --rebuild` | always rebuild, LM Studio step, run Tally |
| `start.bat --create-user <name>` / `--create-sample-db` | unchanged: must be the first argument, the rest pass through to the jar, no LM Studio, no server |
| anything else, including `--coach` together with a subcommand, or a repeated flag | `Unknown option`, usage, exit 1 |

- Option names are case-insensitive, as today.
- **Why reject `--coach` with a subcommand:** subcommands never start a server, so the flag would be silently ignored.

**L3. `tally.local.cmd` and the first-run prompt are unchanged.**
- They are reached only through `--coach`. Existing files keep working. The `fitlocal.local.cmd` → `tally.local.cmd` rename still runs on every start.
- `LM_STUDIO_MODEL` is still set only when `start.bat` loaded a model itself, which now means only with `--coach`.

**L4. Coach error messages no longer point at plain `start.bat`.**

| Case (phase 1 §8.7) | Status | Message |
|---|---|---|
| Connect refused / unreachable | 503 | "Can't reach LM Studio at http://127.0.0.1:1234/v1. Start it (or run start.bat --coach) and try again." |
| No chat model loaded (D6) | 503 | "LM Studio is running but no model is loaded. Load one in LM Studio and try again." |

- **Why:** after L1, plain `start.bat` would fix neither case. With LM Studio already running, rerunning `start.bat --coach` would mean stopping Tally first (the port check), so the no-model message only suggests loading in LM Studio.

**L5. D6 wording.** "`start.bat` loads its configured model only when nothing is loaded" now reads "`start.bat --coach` loads its configured model only when nothing is loaded." The app-side rules of D6 are unchanged.

---

## 2. Scope

### In scope
`start.bat` (L1–L3), the two messages in `LmStudioClient` (L4), README sections that describe startup and LM Studio, and the `start.bat` line in CLAUDE.md Commands.

### Out of scope (do not build)
Everything under "not in this change" in [intent.md](intent.md). Also the phase 1 spec text itself: it stays a dated snapshot, and the **Amends** row records the change.

### Non-functional requirements

| Area | Requirement |
|---|---|
| Footprint | No new dependency or configuration key. |
| Startup | Plain `start.bat` starts no LM Studio process and runs no LM Studio command. |
| Compatibility | `--coach` behaves exactly as `start.bat` did before this change. |

---

## 3. Testing

- **Backend:** `mvnw test` passes. `LmStudioClientTest` checks only the `Can't reach LM Studio` prefix, so no test change is expected. Update any test that pins the full message text.
- **Launcher (manual, no harness exists for batch files):** run from a scratch copy (`start.bat` plus `backend/target/tally.jar` in the same layout), so `cd /d %~dp0` points at a throwaway `data/` and never `data/tally.db`. Check each row of the L2 table, and that plain `start.bat` leaves no `LM Studio.exe` or `lms` process running that wasn't there before.
- Results go in `post-build.md`.

---

## 4. Decision index

| # | Topic | Decision | Date |
|---|---|---|---|
| L1 | Default | Plain `start.bat` skips LM Studio; `--coach` runs the phase 1 step 5 | 2026-11-24 |
| L2 | Arguments | `--rebuild` and `--coach` combine in any order; `--coach` with a subcommand is rejected | 2026-11-24 |
| L3 | Local config | `tally.local.cmd` and the first-run prompt unchanged, reached only via `--coach` | 2026-11-24 |
| L4 | Messages | Coach 503 messages stop suggesting plain `start.bat` | 2026-11-24 |
| L5 | D6 wording | Only `start.bat --coach` loads a model | 2026-11-24 |

---

## 5. Acceptance checklist

- [x] Plain `start.bat` prints the hint line, starts no LM Studio process, and serves Tally on `127.0.0.1:8765` only.
- [x] `start.bat --coach` behaves as `start.bat` did before (uses a loaded model or loads the configured one, API on `127.0.0.1:1234`).
- [x] `--rebuild --coach` and `--coach --rebuild` both rebuild and run the LM Studio step.
- [x] `--coach --create-user x`, a repeated flag and an unknown option print usage and exit 1.
- [x] `--create-user <name>` and `--create-sample-db` behave as before.
- [x] With LM Studio stopped, the Coach tab shows offline and the new "Can't reach" message.
- [x] `mvnw test` passes.
- [x] README and CLAUDE.md describe the new default and `--coach`.
