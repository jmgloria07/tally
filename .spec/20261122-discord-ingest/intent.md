# Discord Log Ingestion: Intent

Logging in Tally today means opening the web app and filling in a form. That is slow when all you want is to note a meal or "squat 5x100". This feature lets you post logs as messages in a Discord channel. Tally picks them up and stores them in the same SQLite database the web app uses.

This file gives the goals and constraints. [spec.md](spec.md) is the normative design, and [plan.md](plan.md) is the build plan. Where they disagree, spec.md wins. Phase 1 documents in [`../.initial/`](../.initial/) still apply, except where spec.md explicitly amends them.

**Guiding rule (unchanged): don't over-engineer.** The first version is deliberately small. It ships a working strict format first and leaves AI parsing of free-form text for later.

## Goals

- **Low-friction logging from anywhere.** Post a message from your phone. You don't have to open Tally or be on the tailnet.
- **The PC doesn't have to be on.** Discord holds the messages until Tally next runs. When Tally starts, it catches up on everything it missed, then handles new messages live while it keeps running.
- **Visible status.** Each message shows its state with a reaction from the bot: processing, logged or failed. The bot replies with what it logged or why it failed.
- **A strict, predictable format.** One entry per line (`meal:`, `lift:`, `weight:`). It works without any AI and costs nothing. Sending `help` in the channel shows the format and an example.
- **Trustworthy data.** A message is logged exactly once and completely, or not at all. Everything goes through the same validation as the web app.
- **Room to grow.** Parsing sits behind one seam, so free-form text parsed by an LLM can be added later without redoing the Discord side.

## Constraints

- **Off by default.** Without Discord configuration, Tally behaves exactly as before, with no outbound connections beyond LM Studio.
- **Opt-in outbound network.** When enabled, Tally connects to Discord. This deliberately relaxes the phase 1 rule that the only outbound call is to LM Studio on localhost.
- **One user, one dataset.** Only messages from allow-listed Discord accounts in one channel are read. Data stays unscoped, as in phase 1.
- **The bot never takes the app down.** A Discord outage or bad token must not stop the web app from working.
- **Secrets stay local.** Tokens live in a gitignored `secrets.properties`, the project-wide home for backend secrets. They and message contents are never logged or committed.

## Explicitly not in this feature

LLM parsing of free-form text (planned next), photos and attachments, undo commands, re-processing edited messages, bot status in the web UI, coaching replies in Discord, multiple channels, running more than one Tally instance against the same channel.
