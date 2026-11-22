# RestClient for LM Studio: Implementation Plan

| | |
|---|---|
| **Source** | [spec.md](spec.md) (normative), [intent.md](intent.md) (goals) |
| **Date** | 2026-11-22 |
| **Rule** | Where this plan and spec.md differ, spec.md wins. Section refs (§) and decision IDs (R) point to spec.md. |

## Working rules

This plan applies the lesson from [`../.initial/README.md`](../.initial/README.md#what-i-got-wrong-one-giant-pull-request): **one milestone, one branch, one pull request.**
- Each PR is reviewed and merged before the next milestone starts.
- Each milestone ends with a **Done when** gate that can be checked.
- Nothing is pushed until it has been reviewed and accepted.
- The PR description lists the decisions (R#) it implements, and says which files deserve a close read and which only need a skim.

This feature lands **before** discord-ingest M1 (R6).

---

## Milestone overview

| # | Milestone | Main output | Tests added | Review closely |
|---|---|---|---|---|
| M0 | Spec | `.spec/20261122-rest-client/` | — | All of it |
| M1 | RestClient switch | `LmStudioClient` on `RestClient` | Chat timeout test | Error mapping (R5), status handling (R3) |

---

## M0 — Spec

- [x] `intent.md`, `spec.md`, `plan.md` in `.spec/20261122-rest-client/`.

**Done when:** the spec is reviewed, every open point is resolved, and the status is set to Approved.

---

## M1 — RestClient switch (R1–R5)

- [ ] Build the shared JDK transport and the three `RestClient`s in the constructor (R2). Constructor signature unchanged.
- [ ] Replace `send(HttpRequest, boolean)` with a helper that runs a `RestClient` request and returns the status and body (R3), using the error mapping in R5.
- [ ] `resolveModel` takes the client to use (models or health) instead of a `Duration`.
- [ ] Chat serializes its body with the existing `JsonMapper` (R4). Parsing helpers unchanged.
- [ ] `// see R#` comments where each decision is applied; class Javadoc updated.
- [ ] New chat-timeout test (§3).
- [ ] README: the architecture diagram label and the stack-table row name `RestClient`.

**Done when:**
- `mvnw verify` passes, with `LmStudioClientTest` unchanged apart from the new test;
- `pom.xml` is unchanged;
- every item in spec §5 is checked, including a manual coach chat against LM Studio and one with it stopped.
