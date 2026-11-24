---
name: tally-screenshots
description: Takes screenshots of Tally from the sandbox with synthetic data, checked for accuracy. Use for images for a LinkedIn post, the README or a demo, especially of the AI coach answering.
---

# Tally screenshots

`$S` is your scratchpad and `$K` is this skill's folder. The real `data/tally.db` never appears in a screenshot: everything comes from the sandbox.

1. **Sandbox.** Start it with the `tally-sandbox` skill and seed it, after a reset if the last seed isn't dated today. For the coach, LM Studio must have a model loaded (`curl -s http://127.0.0.1:1234/api/v0/models`). Done when `http://localhost:8766` serves the seeded data.
2. **Story in the data.** The seed (`tally-sandbox/SeedDemoData.java`) is shaped for one clear story: calories on target, protein about 20 g short every day, weight trending down. If the screenshot needs a different story, change the seed and reseed. Keep the data simple, with one obvious gap: a small model gets clean data right more often.
3. **Answer** (coach screenshots). Ask the coach the chip's prompt (copy it from `CHIPS` in `frontend/src/tabs/Coach.jsx`) 3 times: `node $K/ask-coach.mjs $S/answer-N.json "<prompt>"`. Check every number in each answer against the context the model received (`GET /api/coach/context`, the "at a glance" and daily lines). Done when you have chosen an answer that is at least 80% accurate in the parts the screenshots show and contains one concrete action, and you can name its remaining slips.
4. **Shoot.** `node $K/screenshot-chat.mjs $S/answer-N.json $S/coach-1.png`, and for a long answer a second shot scrolled to a later heading: `node $K/screenshot-chat.mjs $S/answer-N.json $S/coach-2.png 1440 1000 "Gaps in tracking"`. Look at each image. Done when each shows the real UI (signed in, model badge "Connected", no overlay) and the intended sections.

Hand over the image paths, which answer you chose and why, and its remaining slips. Leave the sandbox running and say so.
