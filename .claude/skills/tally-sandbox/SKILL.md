---
name: tally-sandbox
description: Runs a throwaway Tally on its own database and port, optionally filled with synthetic demo data, next to the real one. Use for demos, recording content or screenshots, trying a change without touching real data, resetting or stopping the sandbox, and a live Discord check after changing ingestion code or bumping, pinning or unpinning Discord4J, Reactor, Netty or Spring Boot versions.
---

# Tally sandbox

A second Tally for showing and trying things: its own database (`data/sandbox`), its own port (8766) and, if configured, its own Discord test channel. The real Tally on 8765 can keep running. The sandbox keeps its data between runs until you reset it.

**Guardrails, always:**
- The real `data/tally.db` stays untouched. The sandbox always runs with `TALLY_DATA_DIR=data/sandbox`, and the helpers refuse any other database.
- Discord reads only `sandbox.discord.channel-id` from `secrets.properties`. When that key is blank, start with Discord off. Never fall back to the real `channel-id`: the bot's ✅ marks a message as done, so the real Tally would then skip it.
- Secret values stay out of your output. Check keys in `secrets.properties` by length only (`set (N chars)` or `MISSING`).
- Seeded data is synthetic. Never copy real logs into the sandbox.

Paths: `$S` is your scratchpad directory, and `$CP` is the backend classpath in `$S/cp.txt`. Run commands from the repo root. Pick the section that matches the request; **Start** comes first unless the sandbox is already running.

## Start

1. **Preflight.** Done when you know all three:
   - port 8766 is free (`netstat -ano | grep -E ":8766 .*LISTENING"` prints nothing). If it's taken, the sandbox is probably already running: say so and stop here.
   - whether the real Tally is running (`:8765 .*LISTENING`). That decides step 2.
   - whether `sandbox.discord.channel-id`, `tally.ingest.discord.token` and `tally.ingest.discord.allowed-user-ids` are set, by length. Discord is on only when all three are set.
2. **Build.** If the real Tally is running, its process locks `tally.jar`. Skip the build and tell the human the sandbox runs the last build. Otherwise run `cd backend && ./mvnw -q -DskipTests package`. Either way, if `$S/cp.txt` is missing, run `./mvnw -q dependency:build-classpath -Dmdep.outputFile="$S/cp.txt"` (this works while the jar is locked). Done when `backend/target/tally.jar` exists and `$S/cp.txt` exists.
3. **Launch** in the background, logging to `$S/sandbox.log`:
   - Discord on: `CH=$(sed -n 's/^sandbox\.discord\.channel-id=//p' secrets.properties | tr -d '\r ')` then `TALLY_DATA_DIR=data/sandbox java -jar backend/target/tally.jar --server.port=8766 --tally.ingest.discord.channel-id="$CH"`
   - Discord off: `TALLY_DATA_DIR=data/sandbox java -jar backend/target/tally.jar --server.port=8766 --tally.ingest.discord.token=`

   Wait with a bounded loop until the log shows `Started TallyApplication` or a failure (`Exception`, `Caused by`). With Discord on, also wait for `Discord scan:` or `Discord ingestion is off`. Done when the app started and, with Discord on, `Discord connected` appears. On a failure, stop it and see [Startup failures](#startup-failures).
4. **Login.** `java -cp "$CP" .claude/skills/tally-sandbox/CreateTestUser.java data/sandbox/tally.db`. It creates `tester` / `testtest`, or resets that password if the user exists.
5. **Hand over.** Tell the human:
   - the URL is **`http://localhost:8766`**, not `127.0.0.1:8766`. Cookies are per host name, not per port, so signing in at 127.0.0.1 would sign the browser out of the real Tally.
   - the login, and whether Discord is on (and that it reads the test channel only).
   - that the data stays until they ask for a reset.

   Done when the human has the URL and login.

## Seed (on request)

For a dashboard that looks lived-in: about 21 days of synthetic meals, three workouts a week with progression, and weigh-ins trending down, dated up to today. Run it after Start, on a sandbox with no logs:

`java -cp "$CP" .claude/skills/tally-sandbox/SeedDemoData.java data/sandbox/tally.db`

It refuses a sandbox that already has meals or workouts, so do [Reset](#reset-and-stop) first if asked to reseed. Done when it prints `Seeded …` and the human reloads the page. The coach in the sandbox reads this data too, so it works for recording a weekly review.

## Reset and stop

- **Stop:** stop the background job (TaskStop). The data stays. Done when port 8766 is free.
- **Reset:** Stop, delete `data/sandbox`, then Start again (and Seed if asked). Done when the restarted sandbox shows an empty database (the counts query in [Checks](#checks) returns zeros apart from the login).

## Discord check

A short live test of the real gateway, for after changing ingestion code or bumping Discord4J, Reactor, Netty or Spring Boot. The automated tests use a fake Discord, so they can't catch a broken gateway login. It needs `sandbox.discord.channel-id` set; if it's blank, ask the human for a private test channel ID and to add it to `secrets.properties`.

1. **Start** the sandbox with Discord on, from a fresh build (stop the real Tally if it locks the jar). Done when `Discord connected` appears.
2. **Live round.** Ask the human to post these three messages, then wait for "posted":
   1. ```
      meal: Oats | 420 | 32/60/8 | breakfast
      lift: Squat | 3x5@100
      weight: 72.4
      ```
   2. `meal: Oats`
   3. `help`

   Done when [Checks](#checks) show: message 1 ✅ with a `Logged:` reply and +1 meal, +1 workout, +1 weigh-in, +1 ledger row; message 2 ❌ with `Line 1: meal: needs at least food | kcal` and the help hint; message 3 ✅ with the format reply and no ledger row.
3. **Offline round.** Stop the sandbox, ask the human to post `meal: Rice | 205 | 4/45/0 | offline test`, then Start it again. Done when the startup scan logs `1 pending message(s) queued`, that message gets ✅, and the counts rose by exactly one meal.
4. **Restart.** Restart once more without posting. Done when the scan queues 0 messages and the counts are unchanged.
5. **Hygiene.** Done when both counts are 0:
   - the token in the log: `grep -cF "$(sed -n 's/^tally.ingest.discord.token=//p' secrets.properties | tr -d '\r ')" "$S/sandbox.log"` (prints a count, never the value).
   - message text in the log: grep for `Oats`, `Squat` and `offline test`.
6. **Report** each step as passed or failed with evidence, in chat. Then ask whether to reset the sandbox.

## Checks

- **Discord side:** `java -cp "$CP" .claude/skills/tally-sandbox/ChannelCheck.java secrets.properties 30`. It reads `sandbox.discord.channel-id` and lists messages oldest first with the bot's own reactions (`react=`), replies (`reply-to=`) and the first line of each.
- **Database side:** `java -cp "$CP" .claude/skills/tally-sandbox/DbQuery.java data/sandbox/tally.db "<SELECT>"`. It's read-only and refuses the real `data/tally.db`. Useful queries:
  - `SELECT (SELECT COUNT(*) FROM meals) meals, (SELECT COUNT(*) FROM workouts) workouts, (SELECT COUNT(*) FROM workout_exercises) exercises, (SELECT COUNT(*) FROM workout_sets) sets, (SELECT COUNT(*) FROM weigh_ins) weigh_ins, (SELECT COUNT(*) FROM ingested_messages) ledger, (SELECT COUNT(*) FROM users) users`
  - `SELECT meal_date, food, calories, protein_g, carbs_g, fat_g, notes FROM meals ORDER BY id DESC LIMIT 10`
- **App log:** `grep -E "Message [0-9]+:|Discord|WARN|ERROR" "$S/sandbox.log"` shows each message's outcome by ID.

## Startup failures

- **`ClassNotFoundException` / `NoSuchMethodError` from `reactor.*`, `io.netty.*` or `com.fasterxml.jackson.*`:** version skew. Spring Boot's dependency management overrides the versions Discord4J was built against.
  - Compare `cd backend && ./mvnw dependency:tree -Dincludes=<groupId>` with the versions in Discord4J's own poms (`https://repo1.maven.org/maven2/com/discord4j/discord4j-core/<v>/discord4j-core-<v>.pom` and `discord4j-common`).
  - Pin the version Discord4J needs in `<dependencyManagement>` in `backend/pom.xml`, with a comment saying why and when to remove it. `reactor-extra` 3.6.0 is pinned already for this reason.
  - A REST-only smoke test doesn't catch this: the gateway login path uses different code.
- **`Discord ingestion is off: …`:** a config problem. The message names the key.
- **`Discord login failed (ClientException)` repeating:** the token is wrong or was reset. Ask the human to check `secrets.properties`.
- **Connected, but messages get no reaction and the log warns about missing text:** the Message Content intent is off in the developer portal.
- **Messages get 👀 and then nothing:** a reaction or reply permission is missing in the test channel, or the database failed. The log line `Message <id>: …` names which.
- **Port 8766 in use by something that isn't the sandbox:** pass another port with `--server.port`, and tell the human the URL changed.

## Gotchas

- Spring "default properties" (`SpringApplicationBuilder.properties(...)`) rank below `application.properties`. Pass overrides as `--key=value` arguments or environment variables, or the run writes into `./data`.
- In PowerShell, `set TALLY_DATA_DIR=…` sets nothing; use `$env:TALLY_DATA_DIR = "data/sandbox"`. In Git Bash, use the `VAR=value command` prefix as above. A wrong syntax silently opens the real `data/tally.db`.
- `python` here can hang on stdin, and there's no `jq` or `sqlite3`. Use the Java helpers in this folder, which run on the app's classpath.
- Both Tallys can share one bot token: each instance reads only its own channel.
- **The test login:** `start.bat --create-user` can't be scripted because it reads the password from the console. That's why `CreateTestUser.java` writes the user directly, with the same BCrypt hash and timestamp format as `AuthService.createUser`. If the user schema or the password encoder changes, update that helper and `SeedDemoData.java` too. The fixed password is fine only because the sandbox listens on 127.0.0.1 and is never exposed through Tailscale.
