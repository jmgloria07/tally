---
name: discord-acceptance
description: Manual end-to-end acceptance run of Tally's Discord log ingestion against a real test server and a throwaway database. Use when asked to run or redo the Discord acceptance, to test the bot for real, or after changing ingestion code or the Discord4J/Reactor/Netty dependency versions.
---

# Discord acceptance run

The automated tests use a fake Discord. This run proves the real thing: gateway login, catch-up, live messages, reactions and replies. The human posts the messages; you run Tally, check every outcome, and report against the checklist in `.spec/20261122-discord-ingest/spec.md` §12.

**Guardrails, for the whole run:**
- The real `data/tally.db` stays untouched. Tally always runs with `TALLY_DATA_DIR=data/acceptance`.
- Secret values stay out of your output. Check `secrets.properties` by key presence and length only. The helpers read the token from the file themselves.
- The channel in `secrets.properties` must be a dedicated test channel. Ask if unsure.

Paths: `$S` is your scratchpad directory, and `$CP` is the backend classpath in `$S/cp.txt`. Run commands from the repo root.

## Steps

1. **Preflight.** Done when all three hold:
   - port 8765 is free (`netstat -ano | grep -E ":8765 .*LISTENING"` prints nothing). If it's in use, ask the human to stop their normal Tally, which also unlocks the jar.
   - `token`, `channel-id` and `allowed-user-ids` under `tally.ingest.discord.` in `secrets.properties` are each non-empty. Print only `set (N chars)` or `MISSING`.
   - `data/acceptance` is deleted, so the run starts from an empty database.
2. **Build.** `cd backend && ./mvnw -q -DskipTests package`, then `./mvnw -q dependency:build-classpath -Dmdep.outputFile="$S/cp.txt"`. Done when `backend/target/tally.jar` has a fresh timestamp.
3. **Start.** In the background: `TALLY_DATA_DIR=data/acceptance java -jar backend/target/tally.jar > "$S/acceptance.log" 2>&1`. Then wait with a bounded loop until the log shows `Discord scan:` or a failure (`Discord ingestion is off`, `login failed`, `Exception`, `Caused by`). Done when `Discord connected` and `Discord scan: … queued` both appear. On any failure, stop Tally and go to [Startup failures](#startup-failures).
4. **Live round.** Give the human the [message script](#message-script) round 1 and wait for "posted". Then run [Checks](#checks). Done when every row of the round's expectations matches.
5. **Copy-the-example round.** Ask the human to copy the example from the bot's `help` reply (round 2). Check again. Done when it's ✅ with an echo, and the counts rose by 2 meals, 1 workout with 4 exercises, and 1 weigh-in.
6. **Offline catch-up round.** Stop Tally (TaskStop the background job). Ask the human to post round 3 while it's down. Start it again as in step 3. Done when the startup scan logs `1 pending message(s) queued`, the message gets ✅, and nothing earlier was logged twice (the counts rose by exactly round 3's entries).
7. **Restart idempotency.** Restart once more without posting. Done when the scan queues 0 messages and the counts are unchanged.
8. **Hygiene checks.** Done when both are 0:
   - token in the log: `grep -cF "$(sed -n 's/^tally.ingest.discord.token=//p' secrets.properties | tr -d '\r ')" "$S/acceptance.log"` (prints a count, never the value).
   - message text in the log: grep the log for distinctive words from the script (`Oats`, `Squat`, `felt strong`).
9. **Clean up and report.** Stop Tally and delete `data/acceptance`. Report each spec §12 item as passed, failed (with evidence) or not covered (with the automated test that covers it instead). Put the results in the "Verification" table of `.spec/20261122-discord-ingest/post-build.md`, and ask before touching spec.md. Done when the report and the post-build table agree.

## Message script

Each numbered item is its own Discord message.

**Round 1 (live):**
1. Valid mixed log:
   ```
   meal: Oats | 420 | 32/60/8 | breakfast
   lift: Squat | 3x5@100
   weight: 72.4
   ```
2. `meal: Oats`
3. `help`
4. `// just a comment`
5. An image with no text.
6. *(Optional)* A message in a thread under the channel.

| # | Reactions | Reply | Database |
|---|---|---|---|
| 1 | ✅ (👀 removed) | `Logged:` plus a code block echoing `date:` and the 3 lines | +1 meal, +1 workout (1 exercise, 3 sets), +1 weigh-in, +1 ledger row |
| 2 | ❌ | ``Line 1: meal: needs at least food | kcal`` plus the help hint | unchanged |
| 3 | ✅ only (no 👀 ever) | the format reference and the example | unchanged |
| 4 | none | none | unchanged |
| 5 | ❌ | `Text only. Attachments are not supported.` plus the help hint | unchanged |
| 6 | none | none | unchanged |

**Round 2:** the example copied from the `help` reply, as one message.

**Round 3 (while Tally is stopped):** `meal: Rice | 205 | 4/45/0 | offline test`

## Checks

- **Discord side:** `java -cp "$CP" .claude/skills/discord-acceptance/ChannelCheck.java secrets.properties 30`. This lists messages oldest first with the bot's own reactions (`react=`), replies (`reply-to=`) and the first line of each.
- **Database side:** `java -cp "$CP" .claude/skills/discord-acceptance/DbQuery.java data/acceptance/tally.db "<SELECT>"`. It's read-only and refuses the real `data/tally.db`. Useful queries:
  - `SELECT (SELECT COUNT(*) FROM meals) meals, (SELECT COUNT(*) FROM workouts) workouts, (SELECT COUNT(*) FROM workout_exercises) exercises, (SELECT COUNT(*) FROM workout_sets) sets, (SELECT COUNT(*) FROM weigh_ins) weigh_ins, (SELECT COUNT(*) FROM ingested_messages) ledger`
  - `SELECT meal_date, food, calories, protein_g, carbs_g, fat_g, notes FROM meals ORDER BY id`
  - `SELECT message_id, ingested_at FROM ingested_messages`: every ✅ log message appears exactly once; help and invalid messages never do.
- **App log:** `grep -E "Message [0-9]+:|Discord|WARN|ERROR" "$S/acceptance.log"` shows each message's outcome by ID.

## Startup failures

- **`ClassNotFoundException` / `NoSuchMethodError` from `reactor.*`, `io.netty.*` or `com.fasterxml.jackson.*`:** version skew. Spring Boot's dependency management overrides the versions Discord4J was built against.
  - Compare `cd backend && ./mvnw dependency:tree -Dincludes=<groupId>` with the versions in Discord4J's own poms (`https://repo1.maven.org/maven2/com/discord4j/discord4j-core/<v>/discord4j-core-<v>.pom` and `discord4j-common`).
  - Pin the version Discord4J needs in `<dependencyManagement>` in `backend/pom.xml`, with a comment saying why and when to remove it. `reactor-extra` 3.6.0 is pinned already for this reason.
  - Record the pin in `post-build.md` §2.
  - A REST-only smoke test doesn't catch this: the gateway login path uses different code.
- **`Discord ingestion is off: …`:** a config problem. The message names the key.
- **`Discord login failed (ClientException)` repeating:** the token is wrong or was reset. Ask the human to check `secrets.properties`.
- **Connected, but messages get no reaction and the log warns about missing text:** the Message Content intent is off in the developer portal.
- **Messages get 👀 and then nothing:** a reaction or reply permission is missing, or the database failed. The log line `Message <id>: …` names which.

## Gotchas

- Spring "default properties" (`SpringApplicationBuilder.properties(...)`) rank below `application.properties`. Pass overrides as `--key=value` arguments or environment variables, or the run writes into `./data`.
- `python` here can hang on stdin, and there's no `jq` or `sqlite3`. Use the Java helpers in this folder, which run on the app's classpath.
- `start.bat` and `mvnw package` can't replace `tally.jar` while any Tally process is running.
- **A web login for the human** (optional; the checks don't need one): the acceptance database starts with no users. Only the human can create one, because it prompts for the password. Give the command for their shell. In PowerShell: `$env:TALLY_DATA_DIR = 'data\acceptance'; .\start.bat --create-user tester`, then close that terminal. In `cmd`: `set TALLY_DATA_DIR=data\acceptance` then `start.bat --create-user tester`. A `set` line in PowerShell sets nothing, and the user lands in the real `data/tally.db`. Afterwards, confirm with `DbQuery.java` that the user exists in `data/acceptance/tally.db`.
