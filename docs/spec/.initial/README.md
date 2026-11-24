# Initial Intent

This folder holds the **initial intent** of FitLocal: the documents written *before* any application code existed. They describe what the app should be, how it should be built, and in what order.

FitLocal is my attempt at building an application with an **AI-native software development life cycle (SDLC)**. In this approach, written intent comes first and drives the work. The documents here served as the shared source of truth for me and the AI that helped design, build and test the app.

If you want to develop apps with AI, this folder shows what the starting point looked like in practice.

## What is in this folder

| File | Role | Read it to learn |
|---|---|---|
| [intent.md](intent.md) | **The why and the what.** Goals, stack, data, UI, LLM integration and scope, in about two pages. | What the app is meant to do and the main design choices behind it. |
| [spec.md](spec.md) | **The normative design.** Every design decision with an ID and a reason, plus the data model, API, security, frontend, tests and an acceptance checklist. | Exactly how the app should behave, and why each choice was made. |
| [plan.md](plan.md) | **The build plan.** Milestones (M0–M7), each ending with a "Done when" gate that can be checked. | The order of the work and how each step was verified before moving on. |

The documents form a chain: `intent.md` → `spec.md` → `plan.md`. Each one builds on the one before it, and when two of them disagree, **spec.md wins**.

## The approach

The workflow behind these documents:

1. **State the intent.** Write down the goals, constraints and a guiding rule (here: *don't over-engineer*) in plain language.
2. **Turn the intent into a spec.** Resolve every open question, and record each decision with an ID (for example `C1`, `D6`) and its reason, so code, tests and reviews can point back to it.
3. **Plan in verifiable steps.** Break the spec into milestones, each with a concrete "Done when" check, and don't start the next milestone until the current check passes.
4. **Build against the documents.** Implementation, tests and reviews refer back to the spec instead of relying on what was said in an earlier chat.

Some practices from this project that you can reuse:

- **Give decisions stable IDs.** Code comments such as `// see D6` or `// §8.4` keep the link between the code and the reason it exists.
- **Make the gates testable.** "Done when `git status` is clean and a dummy `data/test.db` stays untracked" can be verified by a person or an AI. "Repo is set up" cannot.
- **Write down what is out of scope.** An explicit list (no streaming, no Docker, no CI, and so on) keeps the AI from adding features nobody asked for.
- **Keep one source of truth.** Saying which document wins in a conflict prevents drift between the documents.

## What I got wrong: one giant pull request

I had the AI build the whole plan, M0 through M7, on a single feature branch. The result was one pull request of about 125 files and 13,500 lines. That turned out to be **the wrong approach**. When I sat down to review it, I couldn't. Nobody can review that much code line by line, and skimming it isn't really a review.

With AI, writing code is no longer the slow part. Reviewing it is. I planned carefully for the build and not at all for the review. Next time I would:

- **Spend most of my review time on the documents.** The intent, the spec and the plan got my closest attention, and that was where it mattered most. When those are right, the code is mostly carrying them out.
- **Make one pull request per milestone.** The plan already had milestones with "Done when" gates. Each one should have been its own branch and its own pull request, a few hundred lines that I review and merge before the next milestone starts. The AI would have kept going for as long as I let it, so stopping it was up to me.
- **Read the tests first.** If the tests capture what the spec says and they pass, the implementation needs less line-by-line attention.
- **Let tools and an AI reviewer go first.** Builds, tests and linters catch whole classes of problems, and an AI review pass can flag issues for me to judge instead of me hunting for them.
- **Match review depth to risk.** I should read authentication, passwords and sessions, the database schema, and what goes to and comes back from the LLM closely. DTOs, wiring and styling only need a skim.
- **Hold myself to "can I explain it?"** I'm the one merging, so I own the code. The bar isn't having read every line. It's being able to explain and debug every module.

To get through the pull request I already had, I reviewed it by risk instead of file by file: the spec, then the schema, then authentication and security, then the LLM integration, then the tests, then a skim of everything else.

## How to read this folder

- These files are a **snapshot** dated 2026-11-21. They show where the project started, not necessarily where it is today. For how the app works now and how to run it, see the [main README](../../../README.md).
- If you only have a few minutes, read [intent.md](intent.md). Read [spec.md](spec.md) §1 for the reasoning behind each decision, and [plan.md](plan.md) to see how the work was ordered and checked.
