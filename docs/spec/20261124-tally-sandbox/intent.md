# Tally Sandbox: Intent

The `discord-acceptance` skill was written for one manual acceptance run of Discord ingestion, and that feature is now accepted. What turned out to be useful was its setup: a throwaway database, Tally started on it, and a test login. That setup was used to record content. As an acceptance tool it got in the way:

- **It replaced the real Tally.** Both used port 8765, so the real Tally had to be stopped.
- **The dashboard was empty on camera.** A fresh database has nothing to show.
- **Switching channels by hand was risky.** Using Discord meant editing `channel-id` in `secrets.properties` and remembering to switch it back. The bot's ✅ is the record of what's done (discord-ingest E1), so a sandbox pointed at the real channel would mark real messages done, and the real Tally would skip them.
- **It deleted its data at the end,** which suits a test run but not a demo you want to record again.

This change turns the skill into `tally-sandbox`: a second, throwaway Tally for demos, screenshots and trying things, with a slim live Discord check kept for dependency changes. [spec.md](spec.md) is the normative design, [plan.md](plan.md) the build plan, and [post-build.md](post-build.md) records how it turned out. Where they disagree, spec.md wins.

**Guiding rule (unchanged): don't over-engineer.** This is developer tooling. The app itself doesn't change.

## Goals

- **Run beside the real Tally.** A separate data folder and port, so the real one keeps running and its data is never opened.
- **Look lived-in on demand.** Optional synthetic data, a few weeks of meals, workouts and weigh-ins dated up to today, so the dashboard, weight chart and coach have something to show.
- **Discord without the edit-and-revert step.** A dedicated test channel set once in `secrets.properties`, and no way for the sandbox to read the real channel.
- **Keep data between runs.** Stop and start again where you left off. Reset only when asked.
- **Keep a live Discord check.** The automated tests use a fake Discord, so a short real-gateway check is still useful after bumping or pinning Discord4J, Reactor, Netty or Spring Boot.

## Constraints

- **No app code changes.** Everything is done with existing settings, command-line overrides and helper scripts in the skill folder.
- **The real database is never touched.** The helpers refuse any database outside the sandbox.
- **Synthetic data only.** Phase 1's rule that no fixture, screenshot or example may contain real data applies to the sandbox too.
- **Secrets stay local.** Secret values are checked by length only and never printed.

## Explicitly not in this change

Demo data inside the app itself (phase 1 keeps "demo seed data" out of scope, and this doesn't change that), a sandbox mode in `start.bat`, more than one sandbox at a time, exposing the sandbox over Tailscale, and the full discord-ingest §12 acceptance checklist (that feature is accepted).
