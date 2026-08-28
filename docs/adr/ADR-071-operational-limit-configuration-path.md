# ADR-071: Give operational limits a configuration path, and rule what a zero means

| Attribute       | Value                                                                     |
|:----------------|:--------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                      |
| **Date**        | 2026-08-18                                                                |
| **Scope**       | `kernel/config`                                                           |
| **Owning Repo** | `exeris-kernel`                                                           |
| **Driven By**   | v0.12 Stream D / T1-4 (1.0-blocking); the 2026-07-31 hardcoded-limits sweep |
| **Compliance**  | [docs/ROADMAP.md](../ROADMAP.md) §"Operational limits"                    |

## Context and Problem Statement

Two of this kernel's HTTP limits are documented as DoS guards, read from configuration, validated at
startup, carried on `HttpConfig` — and enforced by nothing.

`http.maxRequestHeaderCount` and `http.maxRequestHeaderSize` are resolved in
`CommunityHttpConfigResolver` and validated in `HttpConfig.validateRequestLimits`. Both HTTP/1 read
paths — `CommunityHttp1RequestReader`'s codec pass and its header-collection pass — then call the
four-argument `Http1RequestParser.parseHeaders` overload, which substitutes `DEFAULT_MAX_HEADERS`
(100) and `DEFAULT_MAX_HEADER_SIZE` (8 192). The configured values reach no parser on any protocol.

**Two prior records of this were wrong in the same way, and the way matters.** The v0.12 plan and the
July sweep both described an *asymmetry* — "an operator can tune header limits on HTTP/1 and cannot
on HTTP/2". Neither checked for a consumer; both inferred one from the key existing. There is no
asymmetry. There is one dead key on both protocols.

The reachable symptom is not the hypothetical DoS. It is an operator raising the limit to admit a
large legitimate header, and still being refused at 8 192 with nothing in the response or the logs
naming the bound that refused it.

**The second question is what a `0` means**, and the codebase currently answers it three ways:
`idleTimeoutMillis` documents `0 = no timeout`; `maxRequestBodyBytes` documents `-1 = unlimited`;
`maxRequestHeaderCount` and `maxRequestHeaderSize` validate `>= 0` and mean nothing, because nothing
reads them. Adding a configuration path without settling this would spread the inconsistency rather
than the capability.

## 🏁 The Decision

**1. The configured limits are the enforced limits.** `Http1Codec` carries the bounds and passes them
to the parser; production dispatch constructs it from `HttpConfig`. The no-argument constructor
remains for callers with no config in hand and delegates to the parser defaults, so the fallback is
explicit rather than implicit.

**2. Kernel limit keys fall into two classes, and `0` means something different in each.**

| Class | Members today | `0` | Negative |
|:--|:--|:--|:--|
| Capacity / timeout | `idleTimeoutMillis` | **disabled / unbounded** — the already-published contract | refused |
| Protective bound | `maxRequestHeaderCount`, `maxRequestHeaderSize` | **refused at validation** | refused |
| Protective, with a viable "strictest" setting | `maxRequestBodyBytes` | permitted (accept no request bodies) | `-1` = unlimited; below that refused |

**3. The reason `0` is refused for the header bounds is not the one this decision was first framed
with, and the accurate one is narrower.** The concern raised at decision time was that `0` would
switch a protection *off*, so a typo or an empty template slot would silently unguard the server.
Measured against the parser, that is not what happens: `maxHeaders = 0` refuses the first header and
`maxHeaderSize = 0` refuses any non-empty field, so a server configured that way serves nothing but
`400`s. It fails closed, catastrophically, rather than open. Both are values no operator types on
purpose, so both are refused — but the ADR records the mechanism it actually has, because the wrong
mechanism would justify the wrong fix elsewhere.

**4. Refusal happens where the value is named.** `HttpConfig` rejects a non-positive header bound at
construction, and `Http1Codec` rejects one at construction too. A bound that survives to request time
turns an operator's configuration error into what looks like a client error, once per request.

## Consequences

### ✅ Positive Outcomes

- Two documented DoS guards start guarding. Raising a bound admits what it says it admits; lowering
  one refuses what it says it refuses.
- **A default deployment sees no change at all.** `HttpConfig.DEFAULT_MAX_HEADER_COUNT` is 100 and
  `DEFAULT_MAX_HEADER_SIZE` is 8 192 — the same values the parser was substituting. The fix is
  invisible unless you had configured something, in which case it was already not working.
- The `0` question is answered once, in a table, rather than per key by whoever adds the next one.

### ⚠️ Trade-offs

- **A configuration that starts today can be refused after this change.** `maxRequestHeaderCount = 0`
  or `maxRequestHeaderSize = 0` passes the current `>= 0` validation and will now fail at startup.
  Pre-1.0 with no external SPI consumers this is affordable, and the alternative — accepting a value
  that guarantees a total outage — is worse. It is a startup-behaviour change, not merely a new key,
  and is called out as such rather than folded into "adds configuration".
- Two classes of key is more to document than one rule. The alternative considered was a single
  `0 = unbounded` rule everywhere, rejected because `0` is a common empty-template value and the
  protective bounds are exactly where an accidental one must not be honoured.

### 📋 What is NOT in scope

- **HTTP/2 and HPACK bounds.** `Http2HeaderBlockAssembler.MAX_HEADER_BLOCK_SIZE` and
  `HpackDecoder.MAX_STRING_LITERAL` remain hardcoded at 65 536. Giving them a configuration path is
  the next slice; this one makes the h1 keys true first, because a key that lies is worse than a key
  that is absent.

  *Amendment 2026-08-27 — this slice has landed, and it took THREE keys rather than the two
  constants named above. `http.maxHeaderBlockSize` bounds the compressed HEADERS + CONTINUATION
  block, `http.maxStringLiteralSize` replaces the constant named here, and `http.maxHeaderListSize`
  is the one this text did not anticipate: RFC 9113 §6.5.2 defines SETTINGS_MAX_HEADER_LIST_SIZE
  against the CUMULATIVE DECODED field section, a quantity the HPACK decoder was already enforcing
  from a separate hardcoded 65 536 that no constant named above covers. Folding it into the block
  bound would have put a compressed number in the slot the RFC reserves for a decoded one, so the
  server would advertise a limit nothing checks — and asymmetrically, since raising the key is the
  only reason to touch it and raising is the direction that breaks. Three quantities, three keys.*
- **PAQS.** `AdmissionController.MAX_ACTIVE_STREAMS` and `PaqsScheduler.SPIN_THRESHOLD` have no
  configuration surface and no `paqs.*` namespace exists. Same stream, separate slice.

  *Amendment 2026-08-27 — this slice has landed, and it took ONE key rather than the two constants
  named above.* `transport.paqs.maxActiveStreams` carries the admission ceiling on
  `TransportConfig`, under the `transport.*` namespace every wired transport key already uses.
  It has no `network.*` twin, and that is a decision rather than an omission. `network.*` is a
  legacy namespace a resolver consults second only where a `network.*` key had already shipped —
  `CommunityReactorCountResolver` reads `network.reactorCount` for exactly that reason. Nothing has
  ever published `network.paqs.maxActiveStreams`, so a fallback would invent a second name for a key
  that has only ever had one: the duplicate operator-facing surface this decision exists to prevent.
  The `network.paqs.*` block in `config.md` remains what it was: planned, unwired, and mostly
  watermark thresholds rather than PAQS. It falls in this decision's **third class** — protective,
  with a viable unbounded setting — so `-1` means no ceiling, following `maxRequestBodyBytes`, and
  `0` is refused. The reason `0` is
  refused is the mechanism §3 insists on stating accurately: a ceiling of zero admits nothing, so it
  fails closed and catastrophically rather than open.

  *`SPIN_THRESHOLD` did not become a key, and the reason is not sizing.* It is not an operational
  limit at all: it is reachable only from `PaqsScheduler.close()`, decides nothing about which
  streams are served, and bounds only how the shutdown drain spends CPU while waiting — under
  `DRAIN_DEADLINE_NANOS`, which already carries its own reasoned refusal in the same class. Publishing
  it would offer an operator a knob whose only effect is how hot the last milliseconds of a shutdown
  run. Two prior catalogues of this gap listed it beside the admission ceiling as though they were the
  same kind of thing; they are not, and the same catalogues missed the drain deadline, which is the
  constant in that class an operator might actually have a view about.

  *What the ceiling is enforced against was also wrong to leave implicit.* Removing it does not
  remove admission control — the memory-pressure arbiter still decides every stream first — which is
  what makes an unbounded setting defensible for a JVM-controlled deployment rather than simply
  unguarded. The internal normalisation is where this bites in code: the counter is an `int`, so the
  sentinel is turned into `Integer.MAX_VALUE` once at construction. Compared raw, `-1` would shed
  every stream instead of none, and a test that does not hold its slots open passes either way.
- The four `System.getProperty` reads that bypass `ConfigProvider` entirely.

## Cross-references

- `docs/subsystems/http.md` — the HTTP contract these bounds belong to.
- ADR-010 — admission and shedding, for the PAQS bounds this decision defers.

## Engineering Protocol

- `Http1CodecTest` pins the configured bound being the enforced one in both directions, including the
  raised-bound case that admits what the default refuses. Mutation-checked: reverting the codec's
  parser call to the default-substituting overload fails three of the four cases.
- `HttpConfig` validation rejects non-positive header bounds; `Http1Codec` rejects them at
  construction.
- For the PAQS amendment: `AbstractPaqsSchedulerTck$ConfiguredAdmissionCeiling` holds handler slots
  open so the ceiling is what bounds concurrent service, and asserts the slots come back — a
  ceiling is a concurrency bound, not a lifetime quota. Mutation-checked in three directions, each
  with the control staying green: ignoring the configured value reddens three unit cases and both
  TCK cases; comparing the `-1` sentinel raw instead of normalising it reddens *only* the two
  unbounded cases; and dropping the key read from the HTTP listener alone reddens *only* the
  listener's wiring case. The first mutation is what caught the original unbounded TCK case letting
  its handlers return immediately, which passed under any ceiling at all.
