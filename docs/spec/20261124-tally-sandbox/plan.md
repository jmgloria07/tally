# Tally Sandbox: Implementation Plan

| | |
|---|---|
| **Source** | [spec.md](spec.md) (normative), [intent.md](intent.md) (goals) |
| **Date** | 2026-11-24 |
| **Rule** | Where this plan and spec.md differ, spec.md wins. Decision IDs (S) point to spec.md. |

## Working rules

Same as the earlier features: one milestone, one branch, one pull request, each with a **Done when** gate. Nothing is pushed until it has been reviewed and accepted.

This plan is written after the build. The design was approved as a working plan on 2026-11-23 and built the same day. These documents record it in the usual form.

---

## Milestone overview

| # | Milestone | Main output | Review closely |
|---|---|---|---|
| M0 | Spec | `docs/spec/20261124-tally-sandbox/` | All of it |
| M1 | Sandbox skill | `.claude/skills/tally-sandbox/` | S3 (channel key, never the real channel), S8 (guards), `SeedDemoData.java` |

---

## M0 — Spec

- [x] `intent.md`, `spec.md`, `plan.md` and `post-build.md` in `docs/spec/20261124-tally-sandbox/`.

**Done when:** the spec is reviewed and its status is set to Approved.

---

## M1 — Sandbox skill (S1–S8)

- [x] `git mv .claude/skills/discord-acceptance .claude/skills/tally-sandbox` (S1).
- [x] `CreateTestUser.java` and `DbQuery.java` guard on `sandbox` instead of `acceptance` (S8).
- [x] `ChannelCheck.java` reads `sandbox.discord.channel-id` by default, with an optional key argument (S3, S8).
- [x] New `SeedDemoData.java` (S5).
- [x] `SKILL.md` rewritten with the modes in spec §3, the startup-failure diagnosis and the gotchas.
- [x] `secrets.properties.example`: `sandbox.discord.channel-id`, blank, under its own heading (S3).
- [x] README: the "try the bot without touching your data" tip becomes the sandbox tip (S2, S3).
- [x] discord-ingest `post-build.md` §2: one line recording the rename.
- [x] Trigger eval for the new description, with demo requests and close calls.

**Done when:**
- every item in spec §6 is checked, or listed in post-build.md as not yet verified with the reason;
- `mvnw test` passes (a sanity check: no app code changes);
- no file under `backend/` is changed.
