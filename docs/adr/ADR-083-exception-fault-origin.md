# ADR-083: A kernel exception says whose fault it is

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-09-02                                                                                |
| **Scope**       | `kernel/spi`                                                                              |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | v0.12; extends the status split ruled by [ADR-036](ADR-036-server-side-request-body-decoder-spi.md) |
| **Compliance**  | [docs/subsystems/exceptions.md](../subsystems/exceptions.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

Every kernel failure arrives as an `ExerisKernelException` subclass carrying an error code, a
message and `rawArgs`. All three answer **what** went wrong. None answers **whose fault it was**,
and that is the question a protocol adapter must answer before it can pick a status code.

The kernel does state the distinction in exactly one place. ADR-036 §2 puts HTTP status mapping on
the handler — deliberately, because the codec SPI must not know about status codes — and requires a
handler to answer `400` for a body it cannot bind and `5xx` for a decoder it cannot resolve. It
expresses that requirement by naming two exception **types**: `RequestBodyDecodeException` for the
first, `IllegalStateException` for the second.

That works only for a caller who already knows both types by name. Measured against the shipped
hierarchy:

- `RequestBodyDecodeException extends ExerisKernelException extends RuntimeException`, so it is in
  neither JDK hierarchy a handler would reach for. A handler written as
  `catch (IllegalArgumentException) -> 400` never matches it.
- The natural defensive shape, `catch (RuntimeException) -> 500`, turns every malformed request into
  a server error.
- For the other 33 subclasses the question is open and answerable only by reading javadoc one file
  at a time. Nothing in the base class prompts a subclass author to answer it at all.

The consequence is not hypothetical: a body a caller could fix, and a binding an operator must fix,
were being reported identically, and the one that got reported was whichever the handler's `catch`
happened to name first.

**Timing is part of the decision.** The first Maven Central release ships from this line. After it,
however consumers classify kernel failures becomes something they depend on, and correcting it costs
them a migration rather than an upgrade.

## Decision

### 1. Fault origin is a property of the exception, readable from the base class

`ExerisKernelException` gains a non-final `faultOrigin()` returning `FaultOrigin`, a new enum in
`eu.exeris.kernel.spi.exceptions` with two constants:

- **`CALLER`** — the request, its arguments or its credentials are at fault. Repeating it unchanged
  fails identically, and no operator action makes it succeed.
- **`SYSTEM`** — the runtime, its configuration or its dependencies are at fault. The caller can do
  nothing about it, and the same request may succeed once the deployment is fixed.

Putting it on the base class rather than in a side table or a marker interface is what makes the
question unavoidable: a subclass author reads it on the type they are extending.

### 2. The default is the conservative direction, and that is what makes the change additive

`faultOrigin()` returns `SYSTEM` unless a subclass overrides it. That is exactly what the runtime
already did before the method existed — every unclassified failure kept whatever treatment a
`catch (RuntimeException)` gave it — so **classifying further subclasses later adds information
rather than changing behaviour**, and no consumer is broken by a subclass that has not been looked
at yet.

The asymmetry is deliberate and is the reason the default points this way. Reporting a caller's
mistake as a server error produces a worse message. Reporting a broken deployment as the caller's
mistake hides an outage behind a `4xx` that nobody pages on.

### 3. It is not a status code

`FaultOrigin` says who is at fault; it does not say what to answer. HTTP reads `CALLER` as `4xx` but
chooses between `400`, `401`, `403` and `409` from the exception itself, and a non-HTTP binding maps
it somewhere else entirely. Keeping status out of the SPI is what lets the exception hierarchy stay
protocol-blind, which is the same constraint that put status mapping on the handler in ADR-036.

### 4. `FaultOrigin.classify(Throwable)` covers the case every handler has

A handler catches throwables, not kernel exceptions. `classify` applies the same default to anything
that is not an `ExerisKernelException`, so a catch site needs one call rather than an `instanceof`
plus a remembered default:

```java
} catch (RuntimeException failure) {
    respond(FaultOrigin.classify(failure) == FaultOrigin.CALLER
            ? HttpStatus.BAD_REQUEST
            : HttpStatus.INTERNAL_SERVER_ERROR);
}
```

A JDK exception carries no origin, and guessing one from its type is precisely how a bare
`NoSuchElementException` from an unbound kernel binding came to be answered as a bad request.

### 5. Four subclasses are classified; the rest are not, on purpose

Classified `CALLER`, each because the class's own contract sentence leaves no alternative:

| Exception | Why |
|---|---|
| `RequestBodyDecodeException` | the offered bytes are not a valid encoding of the target type — the classification ADR-036 §2 always intended |
| `SecurityAuthenticationException` | the presented credential is expired, malformed or revoked; a valid one succeeds against the same deployment |
| `InsufficientPrivilegesException` | the authenticated principal lacks the privilege; granting it is an authorisation change, not a deployment fix |
| `EventStreamAppendConflictException` | the caller's expected version did not match; re-reading and re-appending succeeds |

The remaining thirty are left at `SYSTEM`. That is not an oversight and not a claim that they are
all deployment faults — it is the default doing its job. Classifying an exception is a judgement
about its contract, and making thirty such judgements in one pass, without the evidence each
deserves, is how a safe default gets replaced by a wrong one.

Two are named here as genuinely open rather than merely untouched. `PathNotFoundException` reports
that a shortest-path search found nothing, which is arguably not a fault at all; and
`ExcessiveAllocationException` fires on a driver churn threshold, where the caller's query and the
deployment's budget are both plausible culprits.

## Consequences

- A consumer writing a protocol adapter against the kernel — with no knowledge of which exception
  types exist — can classify every failure with one call and get the conservative answer for
  anything the kernel has not classified.
- ADR-036 §2's requirement becomes expressible without naming types. The type-based assertion stays
  in the decoder TCK; the origin assertion sits beside it, so a driver that regresses either fails
  the same test.
- `…spi.exceptions` is a `stable` surface. Both additions are binary- and source-compatible; the
  compatibility gate reports an addition, not a break.
- `FaultOrigin` may gain constants — a retryable-transient origin is the obvious candidate — so a
  `switch` over it needs a `default`. This is stated on the enum.

## Alternatives Considered

**Make `RequestBodyDecodeException` extend `IllegalArgumentException`.** Rejected: impossible. Java
has single inheritance and the type must extend `ExerisKernelException` to carry an error code and
`rawArgs`. This constraint is what created the problem, and it applies to every kernel exception, so
no rearrangement of the hierarchy solves it.

**A marker interface (`CallerFault`).** Viable, and rejected on ergonomics rather than correctness. A
marker is invisible from the base class, so it prompts nobody; a subclass author has to already know
it exists. It also cannot express a third origin without a second marker.

**Map error code to origin in a registry.** Rejected: the mapping would live away from the exception
it describes, be keyed by string, and drift silently the first time a code is added without a
registry entry.

**Document the two types and change nothing.** Rejected on the timing argument above. The
classification is about to become a Maven Central contract, and "read the javadoc of thirty-four
classes" is not a contract.

## Compliance and Verification

- `ExerisKernelExceptionGlassBoxTckTest` pins the default, a classified subclass, and both `classify`
  paths (kernel and foreign throwable, including `null`).
- `AbstractHttpRequestBodyDecoderTck` asserts the origin alongside the type it already asserted, so
  ADR-036's contract and this one are enforced by the same case.
- The contract is mutation-checked: flipping the default, making `classify` trust a foreign
  throwable, and dropping the decode exception's override each fail the suite.
