# ADR-045: `HttpRetryPolicy` SPI — Opt-in Client-Side Retry for `KernelWebClient`

**Status:** Reserved
**Date:** 2026-06-25
**Owner:** kernel/transport
**Visibility:** public
**Scope:** kernel/transport (per-repo; lockstep cross-repo coordination with `exeris-tooling`, `budgetHQ`)
**Authors:** Arkadiusz Przychocki
**Resolves the retry deferral named in:** ADR-026 (§"no implicit retry"), ADR-032 (Alternative D)

## Context

The client-side HTTP surface is now codec-complete. `KernelWebClient` (ADR-034) ships
the tier-blind typed facade (`get` / `post` / `patch` / `delete` → `HttpRequestBodyEncoder`
/ `HttpResponseBodyDecoder` registries), and `HttpClientRequestEnricher` (ADR-032) handles
opt-in outbound context propagation. One behaviour was **deliberately deferred** twice and
is now blocking three consumers:

- **ADR-026** (`KernelWebClient` facade): *"The web client deliberately performs no implicit
  retry. Retry policy is the caller's concern; failures map to `WebClientException` exactly
  once."* — a defer, not a decision against retry.
- **ADR-032** (enricher), Alternative D rejected a two-arg `enrich(HttpRequest, HttpClientEngine)`
  contract precisely because *"an enricher with engine access could become a request-level
  retry / circuit-breaker, which ADR-026 explicitly defers."*

The defer has now materialised into a concrete need. Three downstream consumers all require
a retry seam and would otherwise each invent their own, incompatibly:

1. **`exeris-tooling/KernelClientGenerator`** emits per-entity clients (`WidgetClient.findById`,
   `WidgetClient.create`) over `KernelWebClient`. A transient 503 from a sibling service today
   surfaces as a `WebClientException` on the first attempt; the generated client has nowhere to
   express "retry idempotent reads on 503/504 with backoff" without hand-edited code, which the
   generator round-trips away.
2. **The data-migration tool** issues large batches of idempotent writes (`PUT`) against a
   freshly-booted target that may still be admission-shedding (ADR-010 `SHED_LOAD` → 503). Without
   retry, a single transient shed aborts a migration mid-batch.
3. **BudgetHQ** (first real consumer) makes service-to-service calls where a transient transport
   failure (connection reset during a rolling deploy) should not propagate as an application error.

Today every caller would catch `WebClientException`, branch on `status()`, sleep, and re-issue —
the exact per-call-site boilerplate the codec/enricher SPIs were introduced to eliminate. The
retry decision is also **not** an enrichment concern (it owns the send loop and must re-materialise
the request body per attempt), so it cannot live behind `HttpClientRequestEnricher`.

## Decision

Introduce a small implementation-blind retry SPI in `eu.exeris.kernel.spi.http`, consumed by
`KernelWebClient` through one new opt-in constructor parameter — the same composition shape ADR-032
established for the enricher.

### SPI surface (`eu.exeris.kernel.spi.http`)

```java
@FunctionalInterface
public interface HttpRetryPolicy {

    /**
     * Decides whether the attempt that produced {@code outcome} should be retried.
     *
     * @param request      the request as sent (for method / idempotency-key inspection);
     *                     the policy MUST NOT read, retain, or close {@link HttpRequest#body()}
     * @param outcome      the response status OR the transport failure of this attempt
     * @param attemptIndex zero-based index of the attempt that just completed (0 = first try)
     * @return {@link RetryDecision#retryAfter(long)} to retry after a delay, or
     *         {@link RetryDecision#giveUp()} to surface the outcome to the caller
     */
    RetryDecision decide(HttpRequest request, HttpAttemptOutcome outcome, int attemptIndex);

    /** A policy that never retries — preserves ADR-026's "no implicit retry" default. */
    static HttpRetryPolicy none();
}
```

```java
/**
 * The result of a single send attempt: either a received HTTP status (with its response
 * headers), or a transport-level failure with no status. Carries the headers so a policy can
 * honour Retry-After on a 503 — but NO body: body ownership stays inside KernelWebClient.
 *
 * <p>Valhalla-ready value carrier: immutable record, no identity-sensitive operations.
 */
public record HttpAttemptOutcome(int statusCode, List<HttpHeader> responseHeaders, Throwable failure) {

    /** A completed exchange that returned an HTTP status + headers. */
    public static HttpAttemptOutcome ofStatus(int statusCode, List<HttpHeader> responseHeaders) { /* statusCode > 0 */ }

    /** A transport-level failure with no HTTP response (statusCode == 0, empty headers). */
    public static HttpAttemptOutcome ofFailure(Throwable failure) { /* failure != null */ }

    public boolean hasResponse() { return failure == null; }
    public boolean isTransportFailure() { return failure != null; }
}
```

The carrier exposes response *headers* (already an SPI type) but never the response *body* — so a
policy can read `Retry-After` without any `LoanedBuffer` ownership crossing the seam.

```java
/**
 * A retry verdict: retry after {@code delayMillis} (≥ 0), or give up. Immutable value carrier.
 */
public record RetryDecision(boolean retry, long delayMillis) {

    private static final RetryDecision GIVE_UP = new RetryDecision(false, 0L);

    public RetryDecision {
        if (retry && delayMillis < 0L) {
            throw new IllegalArgumentException("retry delayMillis must be >= 0");
        }
    }

    public static RetryDecision retryAfter(long delayMillis) { return new RetryDecision(true, delayMillis); }
    public static RetryDecision giveUp() { return GIVE_UP; }
}
```

### Consumption (`KernelWebClient`, `exeris-kernel-core`)

`KernelWebClient` gains a constructor parameter `HttpRetryPolicy retryPolicy`; the existing
constructors delegate with `HttpRetryPolicy.none()`, preserving the ADR-026 surface for callers
that do not opt in (symmetric with how the 4-arg constructor delegates the enricher to `noop()`).

The retry loop wraps the **existing** send seam in `execute`. Per attempt it re-runs
`buildRequest` → `enricher.enrich` → `engine.send`, classifies the result into an
`HttpAttemptOutcome`, and consults the policy:

```
attempt = 0
loop:
    request  = enrich(buildRequest(method, path, body))   // body re-encoded each attempt
    try:
        response = engine.send(request)
        if 2xx: return decode(response)                   // success path unchanged
        outcome = HttpAttemptOutcome.ofStatus(status)
    catch (transport Throwable t):
        outcome = HttpAttemptOutcome.ofFailure(t)
    decision = retryPolicy.decide(request, outcome, attempt)
    if !decision.retry(): break → throw WebClientException(outcome) // same exception as today
    sleep(decision.delayMillis())                          // on the caller's virtual thread
    attempt++
```

#### Body re-buffering

Each attempt **re-encodes the typed body** from the original domain object via the encoder
registry (`buildRequest` already does this). No `LoanedBuffer` is retained or cloned across
attempts: the loan from attempt *N* is owned and released by `engine.send` exactly as today,
and attempt *N+1* allocates a fresh loan. This sidesteps cross-attempt buffer-ownership entirely —
the retry seam never holds a buffer between iterations.

### Behavioural defaults live in the Community impl, not the SPI

The SPI fixes only the **contract surface** (the part `exeris-tooling` emits against and that must
stay stable). Concrete behaviour — which statuses retry, attempt cap, backoff curve, jitter,
idempotency gate, `SHED_LOAD` handling — ships as a Community implementation
`CommunityHttpRetryPolicy` in `exeris-kernel-community` and can be tuned during consumer alignment
(see Implementation plan §4) **without re-opening this ADR**. The default policy:

- **Idempotency gate.** Retries only requests whose method is idempotent per RFC 9110
  (GET / PUT / DELETE / HEAD / OPTIONS — the enum already documents this), **or** any request
  carrying an `Idempotency-Key` header (caller's explicit opt-in for `POST` / `PATCH`). Non-idempotent
  requests without the header → `giveUp()` immediately.
- **Retryable conditions.** Transport failures (`isTransportFailure()`), and statuses
  `502` / `503` / `504`. `503` is the kernel's admission-shed status (ADR-010 `SHED_LOAD`).
- **`SHED_LOAD` interaction.** On `503`, honour a `Retry-After` header when present; otherwise apply
  capped exponential backoff with full jitter. Attempt cap is low by default (3 total) precisely so a
  fleet of clients retrying into a shedding server does **not** amplify the overload into a retry storm.
  The client-side circuit-breaker (open/half-open transitions across calls) is a Community-impl concern,
  **not** SPI surface — it needs cross-call state the per-attempt `decide` contract intentionally does
  not carry.
- **Backoff + jitter.** Exponential base with full jitter (`delay = random(0, base · 2^attempt)`),
  capped, to decorrelate retries across clients.
- **Give-up.** After the attempt cap, or on any non-retryable status (`4xx`, `5xx` outside the set
  above), or on a non-idempotent request without an idempotency key.

### TCK

`AbstractHttpRetryPolicyTck` (in `exeris-kernel-tck`) pins the observable contract:

- `none()` returns `giveUp()` for every input (zero retries; ADR-026 default preserved).
- Retry decision matrix against the Community impl: `503` on a `GET` → retry; `503` on a `POST`
  without `Idempotency-Key` → give up; `503` on a `POST` **with** `Idempotency-Key` → retry;
  transport failure on a `GET` → retry; `404` → give up; give-up after the attempt cap.
- `SHED_LOAD` shape: `503` + `Retry-After: 2` yields `retryAfter(≈2000)` (Retry-After honoured),
  and the attempt cap bounds total tries (no unbounded retry under sustained shed).
- `RetryDecision` invariant: `retryAfter(negative)` throws; `giveUp()` is a stable singleton.

## Consequences

### Positive

- **Generated-client + migration-tool retry unblocked** without per-call-site boilerplate or
  per-entity codegen: the generator/composition root passes one `CommunityHttpRetryPolicy` and every
  emitted client inherits retry semantics.
- **Codec/enricher/retry now share one composition shape** (opt-in constructor param, `none()`/`noop()`
  default) — consistent, no DI container, no registry magic.
- **No body-ownership hazard.** Re-encode-per-attempt means the retry seam never holds a `LoanedBuffer`
  across iterations; the zero-leak invariant of the codec path is untouched.
- **SPI stays minimal and consumer-stable.** Behavioural tuning lives in the Community impl, so
  consumer alignment can adjust defaults without ADR churn or a generator change.
- **Retry-storm-aware by construction.** The `503`/`SHED_LOAD` default is capped + jittered +
  `Retry-After`-honouring, so the client tier cannot amplify a server-side admission shed (ADR-010).

### Negative / costs

- One additional SPI surface (one interface + two value records + one Abstract TCK) and one Community impl.
- Opt-in asymmetry persists: a caller wanting retry must remember to pass the policy. Mitigation:
  document it in the `http.client` `package-info` and wire it in the generator composition root example.
- Re-encoding the body per attempt costs one extra encode + one extra `LoanedBuffer` allocation per
  retry. Acceptable: retries are the exception, not the hot path, and the alternative (retaining a buffer
  across attempts) reintroduces the ownership hazard this design avoids.

### Neutral / open

- **Cross-call circuit-breaker** (open/half-open across many calls) is **not** SPI surface in this ADR.
  The per-attempt `decide` contract is stateless by design; a stateful breaker is a Community-impl detail
  and, if it ever needs to be swappable, earns its own decision.
- **Per-attempt JFR event** (`HttpClientRetryEvent`: method, attempt index, status/failure, delay) is
  recommended for observability but is an implementation detail of `KernelWebClient`, not SPI — single-phase
  commit only (`[[project_jfr_event_virtual_thread_straddle]]`: never straddle a blocking op on a VT).

## Alternatives considered

### A) Retry inside `HttpClientRequestEnricher` (two-arg `enrich(request, engine)`)

The shape ADR-032 Alternative D already rejected. Still rejected: enrichment is request *decoration*;
retry owns the *send loop* and the body re-materialisation. Conflating them gives the enricher engine
access (a transport concern) and breaks the "enricher never touches the body / never sends" contract.

### B) Retry as an `HttpClientEngine` decorator (transport-level)

Wrap the engine so `send` retries internally. Rejected:
- The engine sees an already-encoded `HttpRequest` with a consumed `LoanedBuffer` — it cannot
  re-materialise the body for attempt *N+1* without retaining/cloning the buffer (the ownership hazard).
- Retry policy needs the *typed* request intent (idempotency of the method, `Idempotency-Key`), which the
  engine layer has by header inspection only and which belongs with the typed facade.
- Pushes a cross-cutting policy below the SPI seam where tooling cannot compose it per application.

### C) Bake default retry behaviour into the SPI (e.g. `HttpRetryPolicy.defaultPolicy()`)

Ship the 503/idempotency/backoff defaults as an SPI static. Rejected:
- Behavioural defaults are exactly what consumer alignment (tooling/migration/BHQ) will tune; freezing
  them in SPI forces an ADR + cross-repo lockstep change for every tuning. Keeping them in the Community
  impl lets the surface stay stable while behaviour evolves pre-1.0.
- An SPI static default would also smuggle a `503`/`Retry-After` opinion into the implementation-blind
  contract, which The Wall keeps free of wire-status policy.

### D) `RetryDecision` as a sealed interface (`Retry(delay)` / `GiveUp`)

Marginally more type-safe than the two-field record. Rejected for now: the record is Valhalla-ready
(scalarizable value carrier, no identity ops) and matches the project's "primitive-friendly carrier"
preference; a sealed hierarchy adds two types and an allocation per decision for no contract gain. The
record's `retry(boolean)` + `delayMillis(long)` is the smaller surface.

## Implementation plan

### v0.10 Sprint 4 (HTTP Client codec/retry — see `docs/local-only/v0.10-sprint-and-implementation-map.md`)

1. **SPI:** `HttpRetryPolicy` + `HttpAttemptOutcome` + `RetryDecision` in `eu.exeris.kernel.spi.http`;
   `HttpRetryPolicy.none()`. Zero preview types in signatures (GA-baseline clean).
2. **Core:** `KernelWebClient` opt-in constructor param + retry loop wrapping the existing `execute`
   send seam; re-encode-per-attempt body; same `WebClientException` on final give-up. Optional
   single-phase `HttpClientRetryEvent` JFR.
3. **Community:** `CommunityHttpRetryPolicy` (idempotency gate, `502/503/504` + transport, `Retry-After`
   honouring, capped exponential backoff + full jitter).
4. **TCK + consumer alignment:** `AbstractHttpRetryPolicyTck` decision matrix; confirm the surface with
   `exeris-tooling` (generator composition root) + the migration tool + `budgetHQ` **before lock**, so the
   stable SPI part needs no post-landing re-work. Behavioural defaults may be tuned in step 3 from that
   alignment without touching this ADR.

### Cross-repo

- `exeris-tooling` — `KernelClientGenerator` composition-root example wires `CommunityHttpRetryPolicy`
  (no per-entity codegen change; the policy is constructor-injected like the enricher). `.link.md` stub.
- `exeris-kernel-enterprise` — `.link.md` stub (consumes the SPI; no enterprise-specific retry surface
  introduced here).
