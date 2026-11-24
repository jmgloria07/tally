# Tally Sandbox: Post-Build Notes

| | |
|---|---|
| **Spec** | [spec.md](spec.md) (approved, unchanged by these notes) |
| **Plan** | [plan.md](plan.md) |
| **Covers** | M1 (uncommitted on `main` at the time of writing) |
| **Date** | 2026-11-24 |

This file records how the build turned out compared with the spec, and what was verified. **The spec is still the source of truth.**

---

## 1. Differences from the spec

None. The spec was written after the build, from the approved plan, so it describes what was built.

## 2. Additions the spec left to the implementation

- **Skill description**, tuned with the trigger eval (§3):
  > Runs a throwaway Tally on its own database and port, optionally filled with synthetic demo data, next to the real one. Use for demos, recording content or screenshots, trying a change without touching real data, resetting or stopping the sandbox, and a live Discord check after changing ingestion code or bumping, pinning or unpinning Discord4J, Reactor, Netty or Spring Boot versions.

  The words "pinning or unpinning … versions" were added by hand after the eval missed the request "try removing the reactor-extra 3.6.0 pin…".
- **Seed contents:** 21 days, fixed random seed 42, two days without meals, and today holding only breakfast and lunch so the "today" totals look in progress. A typical run gives 68 meals, 9 workouts (36 exercises, 95 sets) and 9 weigh-ins, three of them with a body-fat note.
- **Startup failure added:** if port 8766 is taken by something else, use another `--server.port` and tell the human the new URL.

## 3. Verification

| Check (spec §6) | Result |
|---|---|
| Runs beside the real Tally; real DB not opened | **Passed.** The sandbox ran on 8766 while the real Tally ran on 8765. Only `data/sandbox` was created. |
| Key blank: Discord off | **Passed.** Log: `Discord ingestion is off (no tally.ingest.discord.token)`. |
| Test login | **Passed** over HTTP: `POST /api/auth/login` at `localhost:8766` returned 204. |
| Seed fills the dashboard; second seed refused | **Passed.** `/api/dashboard` returned the 30-day weight series, 3 training days in the last 7 and weekly averages. The second seed failed with "the sandbox already has logs". |
| Helpers refuse the real DB | **Passed** for `SeedDemoData` and `DbQuery`. |
| Key set: test channel only | **Passed.** `Discord connected`, then the first scan queued 2 `//` comments (ignored, as designed). `ChannelCheck` read the sandbox key. The real channel wasn't checked directly, but the sandbox was never given its ID. |
| Stop keeps data | **Passed.** Port 8766 was free after stopping, and `data/sandbox` remained. Reset not yet run. |
| Two logins in one browser | **Not verified.** Checked with curl only, not in a browser. |
| S7 Discord check end to end | **Not run.** It needs a human to post in the test channel. |
| Trigger eval | **Passed**, see below. |
| `mvnw test` | **Passed** (exit 0). `verify` wasn't possible while both Tallys locked the jar. |

**Trigger eval.** 25 requests (12 should use the skill, 13 shouldn't, including one about Claude Code's bash sandbox), 3 runs each, with 9 requests held back from tuning:

| Description | Tuning requests | Held-back requests |
|---|---|---|
| As first written | 45/48 (missed the `reactor-extra` pin request) | 27/27 |
| The tuning loop's rewrite | 48/48 | 20/27 (lost two demo and Discord requests) |
| As shipped (manual tweak) | — | — |

The shipped tweak was tested only on the 8 requests nearest the change (24/24 runs correct), not on the full set of 25. The loop's rewrite was rejected because it overfit to the one miss.

## 4. Incidents during the build

- **The trigger-eval scripts don't run on Windows as shipped.** Three workarounds, all kept in the scratchpad rather than the repo:
  - **Pipe reading:** `select()` on pipes isn't supported, so a `sitecustomize.py` patch falls back to blocking reads.
  - **File encoding:** `PYTHONUTF8=1`, because the default cp1252 encoding failed on the emoji in SKILL.md.
  - **One worker:** `--num-workers 1`. Parallel runs each drop a copy of the skill into the same `.claude/commands/` folder, so Claude saw several identical skills and often picked another run's copy, which was scored as a miss. The first smoke test read 0/3; one worker gave 2/2.
- **A stopped eval run left a temporary skill file behind** in the scratch repo copy. It was deleted before the next run.
