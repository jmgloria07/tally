# RestClient for LM Studio: Intent

`LmStudioClient` talks to LM Studio with the JDK `HttpClient`. That means building every `HttpRequest` by hand, setting headers one at a time, turning JSON into strings ourselves, and catching checked exceptions like `IOException` and `InterruptedException` in a hand-written `send` method. It works, but it is plumbing that Spring already provides.

This change moves the client to Spring's `RestClient`. [spec.md](spec.md) is the normative design, and [plan.md](plan.md) is the build plan. Where they disagree, spec.md wins. Phase 1 documents in [`../.initial/`](../.initial/) still apply, except where spec.md explicitly amends them.

**Guiding rule (unchanged): don't over-engineer.**

## Goals

- **Use Spring's current HTTP client.** `RestClient` is the synchronous API Spring recommends. `RestTemplate` is in maintenance mode.
- **Less hand-written plumbing.** Fluent requests and one place that maps transport errors.
- **A base for the next feature.** Discord ingestion (decision E7 there) pulls a shared OpenAI-compatible client out of `LmStudioClient`. Doing this change first means that client starts on `RestClient`.

## Constraints

- **No new dependency.** `RestClient` comes with `spring-web`, which `spring-boot-starter-webmvc` already includes.
- **Coaching behavior doesn't change.** It still picks the same model (D6), uses the same timeouts, and returns the same HTTP statuses and error messages to the browser.
- **The public surface doesn't change.** `LmStudioClient`'s constructor, `chat`, `health` and `Health` stay as they are, so `CoachService`, `CoachController` and the existing tests are not touched.

## Explicitly not in this change

WebClient or anything reactive, streaming, declarative `@HttpExchange` interfaces, the shared OpenAI-compatible client (that is discord-ingest E7), and new configuration keys.
