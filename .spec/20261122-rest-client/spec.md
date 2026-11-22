# RestClient for LM Studio: Requirements & Design Spec

| | |
|---|---|
| **Status** | Approved |
| **Source** | [intent.md](intent.md) |
| **Plan** | [plan.md](plan.md) |
| **Amends** | [`../.initial/spec.md`](../.initial/spec.md) §8.1 (client) |
| **Date** | 2026-11-22 |

This is the normative design for moving `LmStudioClient` from the JDK `HttpClient` to Spring `RestClient`. **Where this spec and intent.md disagree, this spec wins.** Where it disagrees with the phase 1 spec, this spec wins only on the points listed under **Amends**.

Decisions use the prefix **R** (for "RestClient"). Code comments cite them as in phase 1, e.g. `// see R3`.

---

## Contents

1. [Design decisions](#1-design-decisions)
2. [Scope](#2-scope)
3. [Testing](#3-testing)
4. [Decision index](#4-decision-index)
5. [Acceptance checklist](#5-acceptance-checklist)

---

## 1. Design decisions

**R1. Use Spring `RestClient`.**
- **Why not `RestTemplate`:** it is in maintenance mode, and Spring has said it will be deprecated in Framework 7.x. Choosing it now would mean migrating again later.
- **Why not `WebClient`:** it needs WebFlux and Reactor just to make blocking calls. The coach makes one blocking request per turn (phase 1 §8).
- **Why not the JDK `HttpClient` directly (today):** we write the request building, header handling and checked-exception plumbing ourselves, and Spring already provides it.
- No new dependency: `RestClient` is in `spring-web`, which comes with `spring-boot-starter-webmvc`.

**R2. One JDK transport, three `RestClient`s, one per timeout.**
`RestClient` sets timeouts on its request factory, not per request. Phase 1 uses three request timeouts, so:
- One JDK `HttpClient` with a 5 s connect timeout is the transport, used through `JdkClientHttpRequestFactory`.
- Three `RestClient` instances share it, each with its own factory read timeout:

| Client | Read timeout | Used for |
|---|---|---|
| chat | `tally.lm-studio.timeout` (default `180s`) | `POST /v1/chat/completions` |
| models | 5 s | model resolution before a chat (`/api/v0/models`, fallback `/v1/models`) |
| health | 2 s | the same model resolution for `GET /api/health` |

- They are built inside `LmStudioClient` from its existing constructor arguments. There's no Spring `RestClient.Builder` bean and no `spring.http.client.*` configuration, so tests can still construct the client directly.

**R3. Status and body are read manually, never thrown.**
- Every call disables `RestClient`'s default status handling (`onStatus(status -> true, no-op)`) and reads a `ResponseEntity<String>`.
- **Why:** model resolution branches on the status (200 / other 4xx / 5xx). An older LM Studio may also answer an unknown path with an error object and status **200** (phase 1 D6), so the body has to be inspected even on success. Exceptions for 4xx/5xx would only be caught and unwrapped again.
- The 300-character excerpt in `LM Studio returned HTTP <status>: …` is unchanged.

**R4. The existing `JsonMapper` stays.**
- Request bodies are serialized with the client's own `JsonMapper` and sent as a string with `Content-Type: application/json`. Responses are read as strings and parsed as today.
- **Why:** behavior doesn't depend on which message converters Spring registers. The lenient parsing (missing fields, the 200 error object, `UNEXPECTED` on bad JSON) stays exactly as it is.

**R5. Transport errors map exactly as before.**
`RestClient` wraps I/O failures in `ResourceAccessException`. The client walks its cause chain:

| Cause | Chat | Model list / health |
|---|---|---|
| `HttpConnectTimeoutException` or `ConnectException` | 503 "Can't reach LM Studio at …" | 503, same message (health reports offline) |
| `HttpTimeoutException` (read timeout) | 504 "The model took longer than …" | 503 "Can't reach LM Studio at …" |
| any other I/O or `RestClientException` | 503 "Can't reach LM Studio at …" | 503, same message |

- `HttpConnectTimeoutException` is checked before `HttpTimeoutException`, because it is a subclass of it.
- An interrupted thread keeps its interrupt flag, as today.

**R6. This is the base for discord-ingest E7.**
The shared OpenAI-compatible client that Discord ingestion extracts from `LmStudioClient` (bearer key, configurable timeout, error mapping) should build on the R2–R5 setup instead of the JDK `HttpClient`. This spec doesn't change the discord-ingest documents. That session takes this into account when it reaches its M1.

---

## 2. Scope

### In scope
The transport inside `LmStudioClient` (R2–R5), one new test (§3), and the two README lines that name the HTTP client.

### Out of scope (do not build)
Everything under "not in this change" in [intent.md](intent.md). Also: changes to `ModelPicker`, `ReasoningStripper`, `CoachService`, `CoachController`, `LlmException`, configuration keys, or the phase 1 spec text. Phase 1 stays a dated snapshot; the **Amends** row above records the change.

### Non-functional requirements

| Area | Requirement |
|---|---|
| Footprint | No new dependency; `pom.xml` is unchanged. |
| Behavior | Statuses, messages, timeouts and model choice are identical to before. |
| Surface | `LmStudioClient`'s public constructor and methods are unchanged. |

---

## 3. Testing

Follow the phase 1 conventions: JUnit 5, AssertJ, and the JDK's built-in HTTP server as a fake LM Studio. No test talks to a real LM Studio.

- **Existing:** `LmStudioClientTest` passes **unchanged**. It already covers model resolution, the D6 fallbacks, health, and the unreachable 503.
- **New: chat timeout.** A fake `/v1/chat/completions` that answers slower than a 1 s configured timeout returns `LlmException` with `GATEWAY_TIMEOUT` and the "took longer than" message. Nothing tests this path today, and R5's unwrapping is where it is most likely to break.
- **Full suite:** `mvnw verify` passes.

---

## 4. Decision index

| # | Topic | Decision | Date |
|---|---|---|---|
| R1 | Library | Spring `RestClient`; not `RestTemplate` (maintenance) or `WebClient` (reactive) | 2026-11-22 |
| R2 | Timeouts | One JDK transport (5 s connect), three `RestClient`s by read timeout | 2026-11-22 |
| R3 | Status handling | Read status and body manually; no exceptions for 4xx/5xx | 2026-11-22 |
| R4 | JSON | Keep the client's own `JsonMapper` for requests and responses | 2026-11-22 |
| R5 | Errors | Unwrap `ResourceAccessException`; same 503/504 mapping and messages | 2026-11-22 |
| R6 | Follow-up | Discord-ingest E7's shared client builds on this setup | 2026-11-22 |

---

## 5. Acceptance checklist

- [x] `pom.xml` is unchanged.
- [x] `LmStudioClient` no longer imports `java.net.http.HttpRequest` or `HttpResponse`.
- [x] `LmStudioClientTest` is unchanged apart from the new timeout test, and `mvnw verify` passes.
- [x] With LM Studio running and a model loaded, a coach chat answers and health shows the model.
- [x] With LM Studio stopped, health shows offline and chat shows "Can't reach LM Studio at …".
- [x] README no longer says the JDK `HttpClient`.
