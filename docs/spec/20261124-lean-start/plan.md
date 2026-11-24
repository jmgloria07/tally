# Lean start: Implementation Plan

| | |
|---|---|
| **Source** | [spec.md](spec.md) (normative), [intent.md](intent.md) (goals) |
| **Date** | 2026-11-24 |
| **Rule** | Where this plan and spec.md differ, spec.md wins. Section refs (§) and decision IDs (L) point to spec.md. |

## Working rules

**One milestone, one branch (`feature/lean-start`), one pull request.**
- The milestone ends with a **Done when** gate that can be checked.
- Nothing is committed or pushed by the agent. The user reviews the working tree and commits.
- The PR description lists the decisions (L#) it implements.

---

## Milestone overview

| # | Milestone | Main output | Tests added | Review closely |
|---|---|---|---|---|
| M0 | Spec | `docs/spec/20261124-lean-start/` | — | All of it |
| M1 | Lean start | `start.bat` with opt-in `--coach` | — (manual launcher checks) | Argument parsing (L2), subcommand passthrough |

---

## M0 — Spec

- [x] `intent.md`, `spec.md`, `plan.md` in `docs/spec/20261124-lean-start/`.

**Done when:** the spec is reviewed, every open point is resolved, and the status is set to Approved.

---

## M1 — Lean start (L1–L5)

- [x] `start.bat`: header comment and usage text list `--coach` (L2).
- [x] `start.bat`: argument loop that sets `REBUILD` / `COACH`, keeps subcommands first with `%*` passthrough, and rejects the combinations in L2.
- [x] `start.bat`: after the build, go to the LM Studio step only with `COACH`. Otherwise print the hint line and run Tally (L1). Step 5 comment cites L1.
- [x] `LmStudioClient`: the two messages from L4.
- [x] README: Setup, Windows Startup, Operations ("Change the model", plus a `--coach` row), LLM notes (L1, L3, L5).
- [x] CLAUDE.md Commands: `start.bat --coach`.
- [x] `post-build.md`: differences, verification results.

**Done when:**
- `mvnw test` passes;
- every item in spec §5 is checked, with the launcher checks run from a scratch copy (never against `data/tally.db`);
- the changes are left uncommitted for the user's review.
