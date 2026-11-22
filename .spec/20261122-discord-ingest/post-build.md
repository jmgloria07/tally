# Discord Log Ingestion: Post-Build Notes

| | |
|---|---|
| **Spec** | [spec.md](spec.md) (approved, unchanged by these notes) |
| **Plan** | [plan.md](plan.md) |
| **Covers** | M1 (PR #3) and M2 (`feature/discord-ingest-m2-discord`) |
| **Date** | 2026-11-22 |

This file records how the build turned out compared with the approved spec: where the code goes beyond it, where it differs, and what was verified. **The spec is still the source of truth.** Each item here is either accepted as-is, or folded into the spec in a later reviewed change. Open items wait for a decision.

---

## 1. Differences from the spec

| # | Spec | As built | Status |
|---|---|---|---|
| P1 | E8: every ❌ reply ends with ``Send `help` for the format.`` §9: an attachments-only message gets exactly `Text only. Attachments are not supported.` | Changed to follow E8: the attachments-only reply is now `Text only. Attachments are not supported.` plus the help hint. | **Accepted** (hint added). Spec §9 updated to match (approved 2026-11-22). |
| P2 | E15: the README cheat-sheet copies the help reply. | The README shows an example message, the `3x8` gotcha, and points to `help`. The help reply is the only full reference. `ReadmeExamplesTest` fails the build if a README example message stops parsing (validity, not wording). | **Accepted** (2026-11-22). Spec E15, §4 and §12, and plan M2, reworded to match (approved). |
| P3 | §7 lists the keys and defaults only. | Extra validation: `lookback-days` ≥ 1 and `rescan-interval` ≥ 1m. A bad value turns ingestion off with a logged reason (E3), like an incomplete config. | **Accepted** as built; spec §7 now has a "Valid values" column (approved 2026-11-22) |
| P4 | Intent: "nothing leaves your machines" (phase 1 wording). | The README intro now notes the opt-in Discord exception. Follows from E11. | Accept |

## 2. Additions the spec left to the implementation

These are within the spec's "class design is left to the implementation" rule. Recorded so reviewers know where to look.

- **`IngestService.precheck()`** (M2). It returns ignored, help or already-logged without parsing, so the worker adds 👀 only to messages it is about to parse (§3.1 order). `ingest()` calls it first, so behaviour is unchanged.
- **Gateway intents:** `GUILDS`, `GUILD_MESSAGES`, `MESSAGE_CONTENT`. The spec names only Message Content; `GUILDS` is needed for channel lookups.
- **Threads:** excluded by the channel-ID filter. A thread has its own channel ID, so its messages never match (E13).
- **Replies** use Discord's "allowed mentions: none", including the replied-to author, so echoed text can't ping anyone (§8).
- **Version alignment:** Spring Boot's dependency management resolves Discord4J 3.3.3's libraries to Reactor Netty 1.4.0, Netty 4.2.20 and Jackson 2.21.8, not the versions Discord4J was built against.
- **One version pin: `reactor-extra` 3.6.0** in `backend/pom.xml` `<dependencyManagement>`. Boot manages 3.7.0, which removed `reactor.retry`, and Discord4J's gateway login failed at runtime with `ClassNotFoundException: reactor.retry.Backoff`. The REST-only spike didn't exercise that path; the first acceptance start did. Remove the pin when Discord4J stops using `reactor.retry`.
- **Jar size:** about 107 MB with Discord4J and Netty's native transports (including QUIC natives for several platforms). Not trimmed.
- **Acceptance skill:** `.claude/skills/discord-acceptance/` holds the manual run's steps, message script and checks, plus two helpers (`ChannelCheck.java`, `DbQuery.java`).
- **Settings checks** the spec doesn't name (see P3): the channel ID and user IDs must be digits, `start-after` must be a date and `timezone` an IANA zone.

## 3. Verification

| Check | Result |
|---|---|
| M2 spike (plan M2): Discord4J resolves and runs with Boot 4.2 | Passed. A REST call with a fake but well-formed token returned a clean `401 Unauthorized`, so the HTTP and JSON stack works. The gateway (websocket) path is not covered by the spike. |
| `mvnw verify` after M1 | 92 tests, all passing |
| `mvnw verify` after M2 | 117 tests, all passing (worker lifecycle and §9 edge cases against a fake port, settings, startup gating, `secrets.properties` loading, no runner in CLI mode) |
| Manual acceptance (§12) on `TALLY_DATA_DIR=data/acceptance` and a test channel | **Passed** on 2026-11-22. Details in §3.1. |

### 3.1 Manual acceptance results (2026-11-22)

Run with the `discord-acceptance` skill against a private test server. The first attempt failed at gateway login (the `reactor-extra` pin in §2 fixed it). A second run used a channel that turned out not to be a dedicated test channel, so it was redone from scratch on a test channel. Results below are from that final run.

| §12 item | Result | Evidence |
|---|---|---|
| No Discord config: app behaves as before | Passed (automated) | `DiscordIngestWiringTests`. Every non-acceptance start today ran without a token. |
| No `secrets.properties`: app starts normally | Passed (automated) | The test suite runs with no secrets file. |
| Message posted while Tally is off is logged on the next start | Passed | An image posted before start and `meal: Rice …` posted while stopped were both picked up by the startup scan. |
| Message posted while Tally runs is logged within seconds | Passed | ✅ and the echo arrived about 10 s after posting. |
| Bad line: ❌, line number, `help` hint, nothing inserted | Passed | `meal: Oats` and a future `date:`. Database unchanged. |
| `help`: format and example, then ✅; copied example logs | Passed | Also as a reply to the bot. The copied example logged 2 meals, 1 workout (4 exercises, 12 sets), 1 weigh-in, with notes and the workout note intact. |
| Killing Tally mid-processing never duplicates or loses a log | Covered by automated tests | `IngestWorkerTests` (ledger hit after a failed ✅, database error leaves the message pending). Manually: three stop/starts, and counts only rose by new messages. |
| Other user, bot, thread or `//` messages get no reaction | Passed for bot, thread and `//`. Other user: automated only. | The bot's own replies, a thread message and `// just a comment` got nothing. No second human account was available. |
| `secrets.properties` ignored, `.example` tracked | Passed | `git check-ignore` and `git status`. |
| Logs contain no message text or tokens | Passed | All three run logs: 0 token matches, 0 matches for the posted words. |
| README has setup, secrets rule, cheat-sheet | Passed | README "Setup" step 3 and "Logging from Discord". |
| Also verified | `date:` override (entries dated 2026-11-19), future date rejected, attachment-only ❌ "Text only", a web-UI workout saved alongside Discord logs, the restart scan queuing nothing new | |

**Observed, not a defect:** a `//` comment has no reaction, so every scan queues it again and the precheck ignores it. That's cheap (no write) but repeats each pass.

## 4. Incidents during the build

- **Stray `backend/data/` files (M2).** Two new wiring tests passed the data folder as a Spring "default property", which ranks below `application.properties`, so they created `backend/data/tally.db` and `signing.key`. The tests now pass settings as command-line arguments, and the files were deleted. The real `data/tally.db` was never opened. An empty `backend/data/` folder created by an existing test is unrelated.
- **Test login created in the real database (acceptance run).** The instructions for a web login on the acceptance database used `cmd` syntax (`set TALLY_DATA_DIR=…`). Run in PowerShell, that sets nothing, so `start.bat --create-user tester` wrote to the real `data/tally.db` (along with the new, empty `ingested_messages` table that the next normal start would have created anyway). `data/tally.db` was backed up to `data/tally.db.backup-20261122-before-tester-delete`, and the `tester` row deleted in a transaction checked to affect exactly one row. The skill now gives the command per shell.
- **Pre-merge review fixes (on `feature/discord-ingest`).** A two-axis review against `main` found two E3/E4 gaps, now fixed with tests:
  - **Startup could still crash.** `lookback-days` and `rescan-interval` were bound as typed values, so a typo stopped the app even with no token. A too-large `channel-id` made Discord4J throw inside the ready listener. Settings are now bound as raw strings and parsed only when ingestion is enabled, IDs must fit a 64-bit snowflake, and `DiscordIngestRunner.start()` catches any startup failure and leaves ingestion off.
  - **Duplicate replies.** A scan snapshot or re-delivered event of a message that had just been marked ✅/❌ could be processed again, giving a second reply. Data was never duplicated. `IngestWorker` now keeps a bounded in-memory set of messages whose final reaction landed, and skips stale copies. A failed ✅ still leaves the message to be reconciled by the next scan.
- **Further review follow-ups (same branch).**
  - **Macros typo:** a third `meal:` field made only of numbers and slashes (e.g. `30/40`) now fails with a macros message instead of becoming notes, as §4 requires.
  - **Smells:** `IngestService.parseAndLog` lets the worker skip the second ledger check; the scan window moved to `DiscordSettings.windowStart`; the outcome-to-reaction mapping lives only in `IngestWorker`; `StrictLogParser` has one `validated(...)` helper instead of four try/catch blocks; `start-after` reuses `Checks.date`.
  - **Comments:** a stale "README copies it" comment fixed.
  - **Logging (§8):** each handled message now logs its outcome and duration. The adapter's own errors (login, connection) log Discord's HTTP status; failed reaction or reply calls still log only the exception class.
  - **Left as is:** bare `String` IDs and emoji, and long ❌ replies not being trimmed.
- **Spec edited during M1.** Two wording fixes went into spec.md during implementation without prior sign-off: the `ingested_at` comment, and the help example leaving out `date:`. They were reviewed afterwards in PR #3. From M2 on, differences are recorded here instead.
