# RFC-2026-09-01: What representation should a parsed HTTP header field have?

|                    |                                                                          |
|--------------------|--------------------------------------------------------------------------|
| **Status**         | **DRAFT**                                                                |
| **Author(s)**      | Arkadiusz Przychocki                                                     |
| **Date Opened**    | 2026-09-01                                                               |
| **Date Closed**    | —                                                                        |
| **Target ADR(s)**  | TBD — per repo convention a number is reserved when an implementing change reaches its build gate, not at RFC acceptance |
| **Affected Repos** | `exeris-kernel` (authoritative); enterprise tier consumes Core unchanged |
| **Reviewers**      | —                                                                        |

## Question

`HttpHeader` is `record HttpHeader(String name, String value)`, and the HTTP/1 read path
materialises both `String`s from the wire for every field of every request. After the double parse
was collapsed in v0.12 this is what remains, and it is now the dominant per-request allocation.
Should the kernel keep eagerly-materialised `String`s, or adopt a representation that avoids them —
and if so, which one? The answer is bounded by a hard fact established below: the option that saves
the most is the one this runtime cannot make safe.

## Context

`RESEARCH-2026-09-01-http1-header-allocation.md` measured the read path before any fix. A 16-header
request allocated **9 848 B of heap to read ~500 B off the wire**. Collapsing the double parse and
dropping a defensive list copy took it to **5 472 B** — 44%, delivered in v0.12. What is left is
**~345 B per header**, and it is almost entirely token materialisation: two `readAscii` calls per
field, each producing a temporary `byte[]` and a `String` that copies it again.

The v0.12 plan item (T2-10) named `readAscii` from the start. The research corrected its *size* —
materialisation was under half the original figure, not the whole of it — but with the structural
half now removed, the item's original target is what remains. This RFC exists because the obvious
fix touches a published contract, and because the shape of that contract change is one this
repository has already been bitten by once.

`HttpHeader` and `HttpRequest` are classified **stable since 0.5.0** (ADR-009) in
`docs/stability-matrix.md`. That classification is what makes this a decision rather than a patch:
`stable` is the bucket the project commits to being unbreakable on for 1.0. Leaving the question
unanswered costs ~345 B per header per request indefinitely on the path this project's mission
statement is about; answering it wrongly costs a silent cross-request read, which is argued below
rather than asserted.

## Investigation

### Constraint 1 — buffers are pooled, so a stale slice does not fail loudly

This is the decisive fact, and it is the opposite of the intuition that FFM makes dangling access
safe. `CommunityArenaShardPool` returns segments to a per-shard queue on release and hands them to
the next allocation; the backing arena stays open, because "shared-arena semantics require
deterministic pool-level release" (its own comment). Accessing a `MemorySegment` whose *arena* has
closed throws `IllegalStateException`. Accessing one that has merely been **recycled** does not
throw — it reads whatever the pool put there next.

So a header field held as an offset into the request aggregate, read after that aggregate was
recycled, does not crash and does not throw. It returns bytes belonging to **a later request, very
possibly on another connection from another client**. That is a silent cross-request disclosure with
no failure signal, and it is reachable by an application doing something that is legal today:
keeping the `HttpRequest` past the handler call.

### Constraint 2 — the change would be invisible to the compatibility gate

`tools/spi-api-diff` runs japicmp, which compares **signatures**. If `HttpHeader.name()` still
returns `String`, no signature moved, and the gate stays green while the promise behind the method
changes from "a value" to "a value valid only until dispatch returns."

This repository has already named this exact hazard. RFC-2026-08-28 (`RowCursor.getString`) observed
that "narrowing a total function to a partial one is **invisible to the SPI API-diff gate**, which
compares signatures. `japicmp` will not notice a method that starts throwing." Narrowing a value to
a lifetime-bound view is the same class of change, and worse in one respect: the `RowCursor` case
degrades to an exception, this one degrades to wrong bytes.

### Constraint 3 — HTTP/2 cannot adopt the same representation

A wire-slice representation is not protocol-uniform, and the reason is structural rather than
incidental. On HTTP/2 the header block is HPACK-encoded, and HPACK carries a **dynamic table**: a
field in this frame may be a reference to an entry inserted by an *earlier* frame. This is not a
hypothetical about the spec — `Http2SessionContext` holds a per-session `HpackDynamicTable` for each
direction, and `PendingRequestHeaders` receives `String name, String value` from the decoder. A representation
defined as "an offset into the bytes that arrived with this request" is therefore not merely awkward
on h2 — for a table-referenced field it is factually wrong, because those bytes did not arrive with
this request.

Adopting slices for HTTP/1 alone means two representations of the same `stable` carrier with
different lifetime rules, discriminated by a protocol the application is not supposed to care about.

### Constraint 4 — the request outlives the handler frame by design

Dispatch is synchronous inside `CommunityHttpRequestProcessor.process`, so during a handler call the
aggregate is alive. But `CommunityHttpExchange` holds the `HttpRequest` as a **field**, streaming
routes hold that exchange for the stream's whole lifetime, and after a non-streaming request
`retainUnreadBytes` **compacts the aggregate in place** — moving pipelined leftovers to offset 0 —
before `releaseAggregateIfIdle` may return it to the pool. Every offset recorded during the parse is
stale from that moment, while the `HttpRequest` object referencing them is still perfectly reachable.

### Data gathered

Exact per-thread bytes (`ThreadMXBean`), 16-header request, each state in fresh JVMs:

| state | bytes/request |
|---|---:|
| before v0.12 | 9 848 |
| single pass (v0.12) | 5 784 |
| single pass, no list copy (v0.12, shipped) | **5 472** |
| remaining, per header | ~345 |

`readAscii` is already near its floor for producing a `String`: it allocates a temporary `byte[]`,
and `new String(bytes, ISO_8859_1)` copies that array again. There is no public API that builds a
`String` directly from a `MemorySegment` range, so **two arrays per token is the floor for any option
that still produces a `String` for that token.** The only way below it is to not produce one.

That observation is what makes the middle option viable: header *names* are drawn from a small,
highly repetitive set, while values are not.

### Prior art

- **Netty** exposes `AsciiString`/`CharSequence` header names and interns common ones, rather than
  handing out slices with a lifetime rule.
- **HPACK itself** (RFC 7541) is the design answer to this problem for HTTP/2: a shared table of
  previously-seen fields, not per-request byte references.
- In-repo: RFC-2026-08-28 → ADR-080 is the closest precedent for how a `stable` accessor's contract
  gets settled here, and supplied the japicmp-blindness argument used above.

## Options Considered

### Option A: wire-slice header view

`HttpHeader` becomes (or is joined by) a view carrying the aggregate segment plus name/value offsets,
materialising a `String` only if asked. Largest possible saving — approaching zero allocation per
field for handlers that only compare names.

**Pros:**
- Removes essentially all of the remaining ~345 B/header for the common read-only handler.
- Aligns with the project's zero-copy posture on other paths.

**Cons:**
- **Unsafe under pooling.** Per Constraint 1, a retained request reads another request's bytes with
  no exception — a cross-request disclosure, not a crash.
- Turns a `stable` value carrier into a lifetime-bound object, a change japicmp cannot see
  (Constraint 2).
- Cannot be the h2 representation (Constraint 3), so it forks the carrier by protocol.
- Every application holding an `HttpRequest` past the handler becomes silently wrong, with no
  compiler or gate signal.

**Cost:** an ADR, an SPI change to a stable carrier, a lifetime rule the TCK must enforce for every
binding, plus an escape hatch (`toImmutable()`) that most applications would end up calling —
recovering the allocation it was built to avoid.

### Option B: canonical name table, values unchanged

Match the name bytes of each field **byte-wise, case-sensitively** against a table of known header
spellings and reuse the interned constant on a hit; fall back to `readAscii` on a miss. Values keep
being materialised exactly as today.

**Pros:**
- **No SPI change, no contract change, no lifetime rule.** `HttpHeader` stays
  `record(String, String)` and `HttpRequest` stays a value an application may keep forever.
- Byte-identical observable behaviour: a case-sensitive match returns the same characters the wire
  carried, so no header name changes case.
- Applies uniformly to HTTP/1 request parsing, the client response decoder, and — as a second step —
  anywhere HPACK literal names are decoded.
- Reversible. A table is an implementation detail; nothing downstream can tell.

**Cons:**
- Saves only the name half of each field, not the value half.
- Unknown or unusually-cased names get the fallback plus a wasted comparison.
- **The size of the win is not yet measured** (see Open questions).

**Cost:** one lookup structure in Core plus its table; no ADR strictly required, since no boundary
or contract meaning changes — a PR-level change with a research note attached.

### Option C: lazy value materialisation

Keep offsets internally but materialise on first access and cache, so a handler that reads three of
sixteen headers pays for three.

**Pros:** proportional to what the handler actually touches.

**Cons:** inherits **every** hazard of Option A — the first access may simply happen after the
buffer was recycled, so laziness moves *when* the wrong bytes are read without making them right.
Adds mutable state to a carrier the guardrails want Valhalla-ready (no identity-sensitive
operations, immutable).

**Cost:** as Option A, plus the caching state.

### Option D (do nothing)

Keep eager `String`s. v0.12 already removed 44%; the remainder is real but bounded, and the
representation question stays open until a measured consumer need or a post-1.0 window makes the
contract cost affordable.

**Pros:** zero contract risk; `HttpRequest` stays a plain value; no work.

**Cons:** leaves ~345 B/header on the request path indefinitely, on a runtime whose stated premise
is that every allocated byte must earn its place.

## Recommendation

**Take Option B, and reject Option A for 1.0 on safety rather than on effort.**

Option A is the one that looks right for a zero-copy runtime and is the one this runtime cannot
currently make safe. The blocker is not difficulty: it is that `CommunityArenaShardPool` recycles
segments without closing their arena, so the failure mode of a retained header is *wrong bytes from
another request*, delivered silently. A contract that is safe only while every application remembers
not to keep an object it was previously allowed to keep is not a contract this project should put on
a `stable` carrier before 1.0 — particularly when the gate that is supposed to catch published-surface
changes compares signatures and would stay green.

Option B is attractive precisely because it is boring: it changes no contract, no lifetime, no
observable byte, and nothing downstream can detect it. That means it needs no ADR and can be reverted
if the measurement disappoints. It is also the only option that generalises across HTTP/1 requests,
client responses, and HPACK literals without forking the carrier by protocol.

Residual uncertainty is stated plainly: **Option B's benefit is not measured.** The argument that
names are the repetitive half is sound and matches how HPACK's static table was designed, but "sound"
is not a number, and this repository's standard is that a perf claim names the run that proves it.
The recommendation is therefore to accept B *as the direction* and gate the implementation on the
spike in Open questions — if the spike shows the name half is a small fraction of the ~345 B, the
honest outcome is Option D and this RFC closes as REJECTED with the measurement recorded.

### Why not the alternatives?

- **Option A** — its failure mode under the existing buffer pool is a silent cross-request read, and
  the compatibility gate cannot see the contract narrowing that introduces it.
- **Option C** — laziness changes when the wrong bytes are read, not whether they are wrong.
- **Option D** — acceptable as a *fallback if B fails to measure*, but not as a first answer: the
  cost is on the hottest path in the product and the cheapest option carries no contract risk.

### Risks of the recommendation

- **The win may be small.** If real traffic is dominated by long values rather than repeated names,
  B buys little. The spike is designed to find this out before any code lands.
- **Table drift.** A name table is a list of strings that can fall behind reality; it must be
  correctness-neutral by construction — a miss costs a comparison, never a wrong answer.
- **It leaves the value half unaddressed**, so this RFC does not close the zero-copy question. It
  argues the *slice* answer is unavailable until buffer recycling is fenced, and defers the rest.
- **Deferring A has a shelf life.** If a post-1.0 decision makes recycled-segment access fail loudly,
  A becomes reconsiderable on entirely different grounds.

## Decision Record

*(Filled in when status reaches ACCEPTED / REJECTED / WITHDRAWN.)*

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | —     |
| **Date**             | —     |
| **Resulting ADR(s)** | —     |
| **Notes**            | —     |

## Open questions / follow-ups

- **Spike, and it gates the recommendation:** measure the name/value split of the remaining
  ~345 B/header, then measure Option B against a fixture whose names are drawn from real traffic
  rather than the synthetic `X-Request-Header-N` pattern the research fixture used — that pattern is
  adversarial to a name table and would understate B. Fresh JVMs, exact `ThreadMXBean` bytes.
- **The body is copied out of the aggregate on every request with a body.**
  `CommunityHttpRequestProcessor.handleRequest` allocates a `LoanedBuffer` and `MemorySegment.copy`s
  the body into it. The research sweep did not list this because it swept the *header* path. It is
  off-heap rather than heap, so it never appeared in the `ThreadMXBean` figures at all — worth its
  own look, and `LoanedBuffer.peek`/`slice` already exist as the mechanism.
- **Should recycled-segment access fail loudly?** Making the pool hand out segments whose stale
  access throws would change what is possible here, and matters beyond headers. Larger than this RFC.
- **The client response decoder mirrors the server's old defect** — two passes plus per-token
  materialisation. Sequenced behind this RFC deliberately: whichever option wins should be applied to
  it once, not twice.
