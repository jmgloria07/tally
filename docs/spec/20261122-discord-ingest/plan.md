# Discord Log Ingestion: Implementation Plan

| | |
|---|---|
| **Source** | [spec.md](spec.md) (normative), [intent.md](intent.md) (goals) |
| **Date** | 2026-11-22 |
| **Rule** | Where this plan and spec.md differ, spec.md wins. Section refs (§) and decision IDs (E) point to spec.md. |

## Working rules

This plan applies the lesson from [`../.initial/README.md`](../.initial/README.md#what-i-got-wrong-one-giant-pull-request): **one milestone, one branch, one pull request.**
- Each PR is reviewed and merged before the next milestone starts.
- Each milestone ends with a **Done when** gate that can be checked.
- Nothing is pushed until it has been reviewed and accepted.
- Each PR's description lists the decisions (E#) it implements, and says which files deserve a close read and which only need a skim.

---

## Milestone overview

| # | Milestone | Main output | Tests added | Review closely |
|---|---|---|---|---|
| M0 | Spec | `docs/spec/20261122-discord-ingest/` | — | All of it |
| M1 | Strict ingestion | Strict parser, parser seam, ingestion service, ledger table | Parser unit tests, ingestion integration tests | Transaction and ledger, parser rules |
| M2 | Discord | Discord4J adapter, scan and live worker, secrets file, config, docs | Wiring tests, manual acceptance | Skip rules, secrets, logging |

M1 is useful on its own: the ingestion logic is complete and tested before any Discord code exists.

LLM parsing (spec §5) is a separate, later spec with its own plan.

---

## M0 — Spec

- [x] `intent.md`, `spec.md`, `plan.md` in `docs/spec/20261122-discord-ingest/`.
- [x] Main README "How this was built" links to the new folder.

**Done when:** the spec is reviewed, every open point is resolved, and the status is set to Approved.

---

## M1 — Strict ingestion (E2, E5, E10, E14, E15, §4, §6)

- [x] `ingested_messages` in `schema.sql`. Regenerate `data/tally.db.example` with `--create-sample-db`.
- [x] Parser seam: text plus message context in; validated `MealDto.Request` / `WorkoutDto.Request` / `WeighInDto.Request` lists, or a failure with line number and reason, out.
- [x] Strict parser per §4.
- [x] Ingestion service: given a message ID, context and text, it runs the ledger check, parses, then inserts everything plus the ledger row in one transaction through the existing services. It returns an outcome (logged, already logged, invalid) with reply text. No Discord types appear in it.
- [x] Reply formatting per §3.3, including truncation and the `help` hint on ❌.
- [x] Help (E15): the ingestion service recognises `help` and returns the help reply with nothing inserted. A test parses the help example successfully.

**Done when:** strict parser unit tests and ingestion integration tests cover §4 and the data-related rows of §9; `SampleDbTests` passes; `mvnw verify` passes.

---

## M2 — Discord (E1, E3, E4, E8, E9, E11–E13, §3, §7, §8)

- [ ] **Spike first:** add Discord4J and confirm it resolves cleanly alongside Spring Boot 4's managed Reactor and Netty versions, and that the app starts. If it conflicts, stop and raise it before writing any more code.
- [ ] A Discord port (history in a window, add or remove own reaction, reply) with the Discord4J adapter behind it. Reactor types stay inside the adapter (E4).
- [ ] Worker: scan passes (startup, reconnect, periodic) plus live events, queued oldest first, deduplicated, processed one at a time (E4).
- [ ] Skip rules (E13), reactions and replies (E8), failure handling (E9).
- [ ] Startup gating: web mode only, token present, config complete. Otherwise log and stay off (E3).
- [ ] Secrets file (E12):
  - `spring.config.import` line and blank `tally.ingest.*` defaults in the committed `application.properties`;
  - `secrets.properties.example` at the repo root;
  - `.gitignore` entry for `secrets.properties`;
  - the shared test base blanks the token.
- [ ] Logging per §8.
- [ ] Manual acceptance setup:
  - a private test server and channel, and a test bot with the Message Content intent and the §7 permissions;
  - its values go in `secrets.properties`, entered by hand and never pasted into chat or committed;
  - the app runs with `TALLY_DATA_DIR=data/acceptance`.
- [ ] README:
  - a **Configuration** step in Setup: copy `secrets.properties.example` to `secrets.properties` and fill in what you use. Every key is optional. Any backend secret, for any feature, goes in this file (E12).
  - a Discord setup section: creating the bot, the Message Content intent, permissions, which keys to fill in.
  - an example message and a pointer to send `help` in the channel (E15); a test checks every README example parses.
  - the network change in "Security model".

**Done when:**
- ingestion integration tests run through a fake Discord port and cover the lifecycle and edge cases in §9;
- wiring tests pass;
- `git status` shows `secrets.properties` as ignored;
- every item in §12 is checked manually against a real test server and channel, following §12's throwaway setup:
  - `TALLY_DATA_DIR=data/acceptance` (deleted afterwards);
  - a dedicated test channel;
  - the normal instance stopped;
  - the real `data/tally.db` never opened.
