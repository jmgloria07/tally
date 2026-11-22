# Discord Log Ingestion: Requirements & Design Spec

| | |
|---|---|
| **Status** | Draft, for review |
| **Source** | [intent.md](intent.md) |
| **Plan** | [plan.md](plan.md) |
| **Amends** | [`../.initial/spec.md`](../.initial/spec.md) §2 (privacy and footprint rows), §3.4 (configuration) |
| **Date** | 2026-11-22 |

This is the normative design for Discord log ingestion. **Where this spec and intent.md disagree, this spec wins.** Where it disagrees with the phase 1 spec, this spec wins only on the points listed under **Amends**.

Decisions use the prefix **E** (for "external ingest") and continue the phase 1 convention of citing IDs in code comments (e.g. `// see E2`). This spec describes behaviour, constraints and edge cases. It leaves class design, method names and internal structure to the implementation unless a decision names them.

**This version is strict format only.** LLM parsing of free-form text was designed and then deferred, so the first version stays small and works sooner. §5 records what was agreed so a later spec can pick it up. Decisions E6 and E7 are reserved for it.

---

## Contents

1. [Design decisions](#1-design-decisions)
2. [Scope](#2-scope)
3. [Message lifecycle](#3-message-lifecycle)
4. [Strict format](#4-strict-format)
5. [Deferred: LLM parsing](#5-deferred-llm-parsing)
6. [Data](#6-data)
7. [Configuration](#7-configuration)
8. [Security, privacy and logging](#8-security-privacy-and-logging)
9. [Edge cases](#9-edge-cases)
10. [Testing](#10-testing)
11. [Decision index](#11-decision-index)
12. [Acceptance checklist](#12-acceptance-checklist)

---

## 1. Design decisions

**E1. Discord is the record of what is pending.**
A message counts as processed once it carries the bot's ✅ or ❌. Any other message in the scan window is pending, including one with only 👀. There is no cursor and no local queue.
- **Scan window:** messages newer than `now − lookback-days`, and newer than `start-after` if that is set (§7).
- **Why a window, not "stop at the first ✅/❌":** a message whose processing hit a database or Discord error stays pending (E9), while newer messages may succeed. Stopping at the newest ✅/❌ would strand the older one for good. Re-reading a fixed window is a few history pages for a personal channel and needs no ordering assumption.
- Pending messages older than the window are ignored. This is documented, not an error.

**E2. A local ledger makes ingestion atomic and duplicate-free.**
`ingested_messages(message_id PRIMARY KEY, ingested_at)` is written **in the same transaction** as the rows a message produces.
- If a pending message's ID is already in the ledger, it was logged before a crash or failed reaction. The bot inserts nothing and only adds ✅ (§9).
- The ledger only guarantees "at most once". It does not decide what is pending; that remains E1.
- Existing tables are untouched. Meals produce one row per food item, so a per-table unique message ID column wouldn't work.

**E3. The bot runs inside the Tally process, opt-in, and never blocks the app.**
- It starts only in web mode and only when a Discord token is configured. CLI subcommands never start it.
- **Incomplete configuration** (token set but no channel or allow-list) logs one clear error and leaves the bot off. The app still starts.
- **Discord failures** (unreachable, invalid token, gateway drops) are logged and retried with backoff. They never fail startup or affect HTTP requests.
- Running in one process keeps one SQLite writer (pool size 1), so there is no lock contention between processes.

**E4. Transport: Discord4J, gateway for live messages, REST history for catch-up, one sequential worker.**
- **Catch-up:** a scan pass reads channel history over the scan window (E1) and queues pending messages **oldest first**.
- **When scans run:** on startup, after every gateway reconnect, and every `rescan-interval` while running. The periodic pass retries messages left pending by an error and covers any missed gateway events.
- **Live:** gateway message events for the channel go on the same queue.
- **One worker** processes the queue one message at a time. A message already queued or in progress is not queued twice.
- **Reactor stays inside the adapter.** Discord4J is built on Reactor and Reactor Netty. The [RestClient spec](../20261122-rest-client/spec.md) turned down `WebClient` so that Reactor isn't needed for blocking calls (R1). That still holds: Reactor types never leave the Discord adapter, and the worker and ingestion are plain blocking code.

**E5. Parsing sits behind one seam. This version has one parser: strict.**
- The strict parser (§4) produces the existing request DTOs (`MealDto.Request`, `WorkoutDto.Request`, `WeighInDto.Request`), validated by their constructors. DTO validation is the gate.
- The parser sits behind one interface (text plus message context in, validated requests or a failure out), so a later LLM parser (§5) can be added without touching the Discord code or the ingestion service.

**E6, E7.** Reserved for deferred LLM parsing (§5).

**E8. Reactions and replies.**

| State | Reaction | Reply |
|---|---|---|
| Processing | 👀 | — |
| Logged | ✅ (👀 removed) | What was logged (§3.3) |
| Failed | ❌ (👀 removed) | Which line failed and why, ending with ``Send `help` for the format.`` |
| Help (E15) | ✅ | The format reference and an example |

The bot removes only its own reactions.

**E9. Failure classes.**
- **Invalid → ❌:** the strict parse failed, DTO validation failed, or the message is attachments only.
- **Infrastructure → no reaction change:** a database error or Discord API error while processing. The message stays pending without a reply and is retried on the next pass. The error is logged.

**E10. Log date.**
- A message's entries are dated by its `date:` line if present (§4), otherwise by the message's creation time in the configured timezone (§7).
- Dates after the message's local date are invalid (❌).
- Edits never change the date.

**E11. Outbound network (amends phase 1 §2).**
When Discord is configured, the server may connect to Discord's gateway and REST API.
- This is off by default. Without it, the phase 1 rule ("only outbound call is LM Studio on 127.0.0.1") still holds.
- The browser still makes no third-party calls, and the HTTP listener stays on `127.0.0.1`. The bot needs no inbound port.

**E12. Project-wide rule: backend secrets and personal values live in a gitignored `secrets.properties`. Defaults stay in the committed `application.properties`.**
This rule applies to **every** backend feature, now and later, not just Discord ingestion.
- **Committed `backend/src/main/resources/application.properties`:** declares every key with its non-secret default (blank for secrets), in the style of the existing `tally.lm-studio.*` lines. It also has the one line that loads the secrets file: `spring.config.import=optional:file:${TALLY_SECRETS_FILE:./secrets.properties}`.
- **Gitignored `secrets.properties` at the repo root:** holds only secret or personal values (tokens, API keys, account or channel IDs). Its keys override the defaults.
  - It is `optional`, so a missing file means features that need it stay off. Nothing else breaks.
  - `start.bat` runs from the repo root, so the relative path resolves there. `TALLY_SECRETS_FILE` points elsewhere, e.g. for runs started from `backend/`.
- **Committed `secrets.properties.example`:** lists every key that belongs in `secrets.properties`, with empty values and a comment per key. A feature that adds a secret adds its key here in the same change.
- **Why not `src/main/resources`:** that file is committed and packaged into `tally.jar`. Secrets there could be pushed by accident and would be baked into the build.
- **Why not `application.properties` as the name:** the name says what the file holds, not which framework reads it.
- **Why not an `application.properties.example`:** that would mean gitignoring the real file. Then a fresh clone couldn't build or test until someone copied it, secrets would still end up in the jar, and the two copies would drift.
- Environment variables still override both files through Spring's relaxed binding (e.g. `TALLY_INGEST_DISCORD_TOKEN`).

**E13. Who and what is read.**
- **Only** the configured channel, and **only** messages authored by allow-listed Discord user IDs. Everything else, including other bots, webhooks and the bot's own replies, is ignored silently with no reaction.
- Messages in threads are ignored.
- A message whose text starts with `//` (after trimming) is a comment: it is ignored with no reaction.

**E14. One message, one transaction. Corrections are new messages.**
- All entries in a message are inserted together or not at all.
- Editing a processed message does nothing. Editing a pending message is fine: the latest text is read when it is processed.
- There is no undo. Mistakes are fixed in the web app or by posting a new message.

**E15. `help` replies with the format and an example.**
- A message whose whole text is `help` (case-insensitive, after trimming) gets a reply with the strict format reference (§4) and a complete example message. It is then marked ✅, so later scan passes treat it as processed (E1).
- It inserts nothing and writes no ledger row. If Tally crashes between the reply and the ✅, the next pass replies again. That's harmless.
- It is a plain text keyword, not a Discord slash command (slash commands stay out of scope). `help` with anything else in the message is parsed as a normal log, fails, and gets ❌ with the hint to send `help`.
- **One source for the format text.** The help reply is the canonical cheat-sheet. The README cheat-sheet copies it. A test parses the help example with the strict parser and requires it to succeed, so the example can't drift from the grammar.

---

## 2. Scope

### In scope
Everything in [intent.md](intent.md) except its "not in this feature" list, as detailed here: meals, workouts and weigh-ins from one channel in the strict format, plus the project-wide secrets file (E12).

### Out of scope (do not build)
LLM parsing (§5), attachments and photos, undo commands, re-processing edits, bot status in the web UI, coaching or any other LLM reply in Discord, slash commands, multiple channels or servers, DMs, per-user data scoping, running several Tally instances against one channel.

### Non-functional requirements (additions)

| Area | Requirement |
|---|---|
| Footprint | One new backend dependency: Discord4J (core). No other new runtime dependency. |
| Latency | A live message reaches ✅/❌ within seconds. Speed is not otherwise a goal. |
| Resilience | Restarting Tally at any moment never loses or duplicates a log (E1, E2). |

---

## 3. Message lifecycle

### 3.1 Per message

1. **Skip conditions:** not from an allow-listed author, in a thread, a `//` comment, or already carrying ✅/❌ (E1, E13). Skipped messages get no reaction.
2. **Help:** if the text is `help`, reply with the format and an example, add ✅, and stop (E15).
3. **Ledger check:** if the message ID is in `ingested_messages`, add ✅, remove 👀, reply `Already logged.`, and stop (E2).
4. **Add 👀.**
5. **Parse** (E5).
6. **On success:** in one transaction, insert all entries plus the ledger row (E2, E14). Then set ✅ and reply.
7. **On failure:** set ❌ and reply for an invalid message, or leave the reactions as they are for an infrastructure failure (E9).

Reaction and reply calls are best-effort: their failure is logged and never rolls back data. The next scan pass reconciles via the ledger.

### 3.2 Message context given to the parser
The message text, its creation time as a local date-time, and the timezone ID.

### 3.3 Reply content
- **✅ reply:** each logged entry on its own line, in the strict format, so you can copy, fix and repost it.
- **❌ reply:** the line number and the reason, then ``Send `help` for the format.``
- **Help reply (E15):** the §4 format reference, written for a phone screen (one line per entry type with a short gloss), followed by an example message that uses every line type and the main set-group forms.
- Replies longer than Discord's message limit are truncated with a line saying how many entries were omitted. The data is still logged in full.

---

## 4. Strict format

One entry per line. Keywords are case-insensitive, and surrounding whitespace is ignored. Blank lines are allowed. Types can be mixed in one message. Fields are separated by `|`. Any line that doesn't match makes the whole message fail (❌ naming the first bad line).

```
date: 2026-11-21                         optional; at most once; must be the first non-blank line
meal: <food> | <kcal> [| <p>/<c>/<f>] [| <notes>]
lift: <exercise> | <sets> [| <notes>]
weight: <kg> [| <notes>]
note: <text>                             workout-level notes; at most once
```

- **meal:** one line is one `meals` row. Macros are grams and optional as a group (missing means 0, the existing DTO default). A meal name like "lunch" goes in notes.
- **lift:** all `lift:` lines in a message form **one** workout, with exercises in line order. The same exercise name may appear twice and becomes two exercises.
- **`<sets>`** is a comma-separated list of set groups. Each group is one of:
  - `R x W`: one set of R reps at W kg (e.g. `5x100`)
  - `N x R @ W`: N sets of R reps at W kg (e.g. `3x5@100`)
  - `R,R,R @ W`: listed reps, all at W kg (e.g. `5,5,4@70`). The `@ W` applies to the reps since the previous group.
  - A bare `R` or `R,R,R` with no weight means 0 kg (bodyweight).
- **Weights are kg only.** A number may have a decimal point. A `kg` suffix is allowed. `lb`/`lbs` is rejected with a message pointing to kg.
- **weight:** creates one weigh-in. Body composition goes in notes, as in phase 1 (`BF: 17.5%, MM: 37%`).
- **note:** without any `lift:` line is invalid.
- Limits are the existing DTO limits (text lengths, 50 exercises, 50 sets, value ranges). Error messages name the line number.

Exact tokenisation (spacing around `x` and `@`, upper-case `X`) is left to the implementation, but it must be covered by tests and shown in the help reply (E15), which the README cheat-sheet copies.

---

## 5. Deferred: LLM parsing

Not built in this version. These are the directions agreed during design, recorded so a later spec starts from them and doesn't reopen them. That later spec owns the final decisions under E6 and E7.

- **Order:** strict first, then the LLM only when strict fails and an LLM is configured. Strict messages never cost an LLM call.
- **Provider:** any OpenAI-compatible chat completions API (Claude through Anthropic's compatible endpoint, OpenAI, OpenRouter, LM Studio). Separate `tally.ingest.llm.*` config; key and URL in `secrets.properties` (E12).
- **Client:** a shared client extracted from `LmStudioClient` on the RestClient setup (RestClient spec R2–R6). It reports provider-neutral errors, and `LmStudioClient` keeps its exact messages.
- **Output:** JSON requested in the prompt, extracted leniently (reasoning stripped, fences removed), and validated by the same DTOs. It estimates macros when they aren't given, marked `est.` in notes and in the reply. The reply says which parser ran.
- **Data sent:** only the message text, its local timestamp and the timezone.
- **New state ⏳:** when the LLM is configured but unavailable (credits, outage), the message gets ⏳ and one reply, stays pending, and is retried on every pass.
- **Known issue to solve then:** reposting a ⏳ message in strict format can lead to a duplicate when the LLM later recovers.

---

## 6. Data

### 6.1 Schema addition (`schema.sql`)

```sql
CREATE TABLE IF NOT EXISTS ingested_messages (
    message_id  TEXT PRIMARY KEY,   -- Discord snowflake as text
    ingested_at TEXT NOT NULL       -- ISO-8601 instant
);
```

- `CREATE TABLE IF NOT EXISTS` means existing databases pick it up on startup with no manual `ALTER`.
- The sample DB (`data/tally.db.example`) must be regenerated with `--create-sample-db`, and `SampleDbTests` must pass.
- Rows are never deleted by the app. The table grows by one short row per logged message.
- It isn't linked to the inserted rows. Deleting a meal in the web app doesn't make its message eligible again.

### 6.2 Writes
Ingestion creates entries through the existing services' business logic, inside one surrounding transaction (E14). It doesn't duplicate their validation or mapping. Workouts from Discord don't set `submission_id`; the ledger covers retries.

---

## 7. Configuration

All keys live under `tally.ingest.*` (amends phase 1 §3.4). Defaults are declared in the committed `application.properties`. Rows marked *secret* go in `secrets.properties` and `secrets.properties.example` (E12).

| Key | Default | Meaning |
|---|---|---|
| `tally.ingest.discord.token` | blank | *Secret.* Bot token. Blank means the bot is off (E3). |
| `tally.ingest.discord.channel-id` | blank | *Secret.* The one channel to read. Required when the token is set. |
| `tally.ingest.discord.allowed-user-ids` | blank | *Secret.* Comma-separated Discord user IDs. Required when the token is set. |
| `tally.ingest.discord.lookback-days` | `14` | Scan window (E1). |
| `tally.ingest.discord.start-after` | blank | Optional ISO date. Messages before it are never ingested (e.g. to skip a channel's history on first run). |
| `tally.ingest.discord.rescan-interval` | `15m` | Periodic scan pass (E4). |
| `tally.ingest.timezone` | blank | IANA zone for log dates. Blank means the system default. |

`.gitignore` gains `secrets.properties`. `secrets.properties.example` stays tracked.

**Discord setup (for the README):** create an application and bot, enable the **Message Content** privileged intent, and invite the bot to the server with View Channel, Read Message History, Send Messages and Add Reactions on the log channel.

---

## 8. Security, privacy and logging

- **Trust boundary:** only allow-listed authors' text is ever parsed (E13), and only into DTO requests that pass validation.
- **What leaves the machine:** nothing new beyond Discord itself. Everything Tally writes back to Discord is your own data, echoed into your own channel.
- **Logging:** never log message text, tokens or keys. Log message IDs, outcomes (logged, failed, skipped as a duplicate), durations and error classes (e.g. `Discord 403`).
- **Secrets:** only in `secrets.properties` or the environment (E12). Never in the committed `application.properties`, tests or fixtures. Tests use fake tokens.

---

## 9. Edge cases

| Situation | Behaviour |
|---|---|
| Crash after 👀, before insert | Still pending (no ✅/❌, not in ledger). Processed again on the next pass. |
| Crash after insert, before ✅ | Ledger hit on the next pass: ✅ plus `Already logged.`, nothing inserted (E2). |
| Database error while inserting | Rolled back. No reaction change. Retried on the next pass (E9). |
| Reaction or reply call fails (permissions, rate limit) | Logged. Data stays. Reconciled on the next pass via the ledger. |
| Message deleted while processing | Reaction or reply fails and is logged. Inserted rows stay. |
| Pending message edited | The latest text is used when processed. |
| Processed message edited | Ignored (E14). |
| Pending message older than the scan window | Ignored (E1). |
| Message has text and attachments | Attachments ignored, text parsed. |
| Message has attachments only | ❌ `Text only. Attachments are not supported.` |
| Allow-listed message with empty text and no attachments | Usually means the Message Content intent is off. No reaction. Logged as a warning naming the likely cause. |
| `date:` later than the message's local date | ❌. |
| Empty message after removing blank lines | Ignored. |
| `help` / `HELP` / ` help ` | Help reply plus ✅ (E15). |
| `help` plus other text | Parsed as a log. ❌ with the `help` hint. |
| Crash after the help reply, before ✅ | Help is sent again on the next pass. |
| Free text or any non-matching line | ❌ naming the first bad line. |
| Exceeds DTO limits (e.g. 51 exercises) | ❌ with the validation message. |
| Discord unreachable or bad token at startup | App starts. Bot logs and retries with backoff (E3). |
| Gateway reconnects | A scan pass runs (E4). |
| Same message arrives live and in a scan | Queued once (E4). The ledger is the final guard. |
| Two Tally instances on one channel | Unsupported (§2). The ledger prevents duplicates only within one database. |
| Web app writing at the same time | Normal. One process, pool size 1, short transactions. |

---

## 10. Testing

Follow the phase 1 conventions: JUnit 5, AssertJ, `IntegrationTest` base with a temporary SQLite file and a fixed clock, synthetic data only. **No test talks to real Discord.**

- **Strict parser (unit):** every line type, every set-group form, bodyweight, kg suffix, lb rejection, notes per entry, `date:` and `note:` rules, future dates, line-numbered errors, limits. **The help example parses successfully** (E15).
- **Ingestion (integration):** a fake Discord port (history, reactions, replies) drives the lifecycle in §3 and the edge cases in §9. Covered: atomic insert plus ledger; ledger hit; help reply plus ✅ with nothing inserted; database error leaves the message pending; skip rules; scan window; oldest-first order; reaction failures don't roll back data.
- **Wiring:** with no token, the context loads with the bot off. With an incomplete config, the context loads, the bot is off, and an error is logged. CLI modes never start the bot. With no `secrets.properties`, the app starts normally. The shared test base sets the token to blank, so a developer's real `secrets.properties` (or `TALLY_SECRETS_FILE`) can never make a test connect out.
- **Sample DB:** `SampleDbTests` passes after regeneration.

---

## 11. Decision index

| # | Topic | Decision | Date |
|---|---|---|---|
| E1 | Pending state | Discord reactions are the record; fixed scan window | 2026-11-22 |
| E2 | Duplicates | `ingested_messages` ledger in the same transaction | 2026-11-22 |
| E3 | Hosting | In-process, opt-in, never blocks the app | 2026-11-22 |
| E4 | Transport | Discord4J gateway plus REST history scans; one sequential worker; Reactor stays in the adapter | 2026-11-22 |
| E5 | Parsing | One parser seam; strict parser only; DTO validation is the gate | 2026-11-22 |
| E6, E7 | LLM parsing | Deferred (§5) | 2026-11-22 |
| E8 | Feedback | 👀 ✅ ❌ plus replies | 2026-11-22 |
| E9 | Failures | Invalid → ❌; infrastructure → retry silently | 2026-11-22 |
| E10 | Log date | `date:` line, else message time in the configured timezone; no future dates | 2026-11-22 |
| E11 | Network | Discord only, opt-in (amends phase 1 §2) | 2026-11-22 |
| E12 | Secrets | Project-wide: gitignored `secrets.properties` (imported, path overridable) plus committed `.example`; defaults in committed `application.properties` | 2026-11-22 |
| E13 | Inputs read | One channel, allow-listed authors, no threads, `//` comments | 2026-11-22 |
| E14 | Atomicity | One message is one transaction; corrections are new messages | 2026-11-22 |
| E15 | Help | `help` replies with the format and an example, then ✅; the example is tested against the parser | 2026-11-22 |

---

## 12. Acceptance checklist

**Run the manual checks against a throwaway setup, never your real data:**
- Start Tally with `TALLY_DATA_DIR=data/acceptance`. It's under the gitignored `data/`, so the app creates a fresh database, schema and signing key there, and the real `data/tally.db` is never opened. Delete the folder afterwards.
- Use a dedicated test channel, ideally on a private test server, not the channel your real Tally will read. Otherwise the real instance's first catch-up would import the test messages: they carry ✅ in Discord, but the real ledger has never seen them.
- Stop any normal Tally instance first. Both bind to `127.0.0.1:8765`.

- [ ] No Discord config: the app behaves as before, and no outbound connection other than LM Studio is attempted.
- [ ] With no `secrets.properties` at all, the app starts normally with the feature off.
- [ ] Strict message posted while Tally is off is logged on the next start: 👀 then ✅, with an echo reply.
- [ ] Strict message posted while Tally is running is logged within seconds.
- [ ] Bad line: ❌ with the line number and the `help` hint. Nothing inserted.
- [ ] `help`: reply with the format and an example, then ✅. Copying the example as a new message logs it with ✅.
- [ ] Killing Tally mid-processing and restarting never duplicates or loses a log.
- [ ] Messages from another user, a bot, a thread, or starting with `//` get no reaction.
- [ ] `secrets.properties` is ignored by git, and `secrets.properties.example` is tracked with empty values.
- [ ] Logs contain no message text or tokens.
- [ ] README has the Discord setup steps, the secrets-file rule and the strict format cheat-sheet.
