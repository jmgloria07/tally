# Lean start: Intent

Every `start.bat` run starts LM Studio as well. It opens the desktop app (then waits 12 s), loads the coach model if none is loaded (a 20K-context model at max GPU offload), starts the API, and waits up to 80 s for it. This takes VRAM, RAM and startup time, and most of the time the AI coach isn't used. The tracker itself (logging workouts, meals, weigh-ins, Discord ingest) never needs LM Studio.

This change makes plain `start.bat` start Tally only. LM Studio becomes opt-in, either with a new `start.bat --coach` or by starting LM Studio and loading a model by hand. [spec.md](spec.md) is the normative design, and [plan.md](plan.md) is the build plan. Where they disagree, spec.md wins. Phase 1 documents in [`../.initial/`](../.initial/) still apply, except where spec.md explicitly amends them.

**Guiding rule (unchanged): don't over-engineer.**

## Goals

- **Lean default startup.** Plain `start.bat` doesn't touch LM Studio: no process start, no model load, no API wait. Other applications on the PC get its GPU and memory.
- **The coach still works on demand.** The app already asks LM Studio which model is loaded on every coach question (phase 1 D6), and the Coach tab shows online/offline. Starting LM Studio later, with or without `--coach`, needs no Tally restart.
- **Keep what works.** The existing LM Studio logic (use an already-loaded model, the first-run model prompt, `tally.local.cmd`, binding the API to 127.0.0.1) stays, behind `--coach`.

## Constraints

- **No app behaviour change beyond two error messages**, which stop suggesting that plain `start.bat` fixes a missing model.
- **Existing `tally.local.cmd` files keep working** unchanged.
- **No new dependency or configuration key.**

## Explicitly not in this change

Removing the LM Studio logic, a config-file switch for the default, starting or stopping LM Studio from the web UI, unloading models when Tally stops, and changes to Discord ingest's LLM settings.
