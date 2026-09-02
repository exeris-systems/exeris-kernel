# Research: what the HTTP/1 read path allocates per request

| | |
|---|---|
| **Branch** | `research/http1-header-allocation` |
| **Date** | 2026-09-01 |
| **Driver** | v0.12 T2-10 — *"the HTTP/1 header path materializes a `String` per token"* |
| **Status** | `concluded` — measured, disposition **Promote to Feature**. The fix is not in this branch. |

## Hypothesis

*The per-request allocation of the HTTP/1 read path is dominated by token materialisation in
`Http1RequestParser.readAscii`, and is large enough relative to the request's wire size to justify a
zero-copy header representation.*

Falsifiable: if the read path allocated on the order of the request's own bytes, the answer would be
"leave it" and T2-10 would close as a non-item.

## What was held fixed

Allocation, not throughput — so the usual driver/warm-up confounds do not apply, and no HTTP load
generator is involved. Exact per-thread bytes via `ThreadMXBean.getThreadAllocatedBytes`, **not** JFR
`ObjectAllocationSample`: that event reports `weight`, a sampler extrapolation in a near-constant
quantum, and reading it as a byte count is the defect the graph churn TCK carried for three releases.

20 000 warm-up iterations, 50 000 measured, median of three windows, then the whole thing repeated in
**fresh JVMs** — windows inside one process are one sample.

**The harnesses do not run in the build, by construction.** They are named `*Research`, and the
parent POM configures no surefire includes, so the plugin's defaults (`*Test`, `Test*`, `*Tests`)
skip them — they run only under an explicit `-Dtest=`. That is what research code should do, and it
was confirmed rather than assumed: a full `mvn clean install` left the reactor total unchanged at
3 809, which is the check that would have caught the opposite.

## Runs

Two harnesses. One measures a **single** parser pass; the other measures `tryParseRequest`, which is
what a request actually goes through.

| headers | one parser pass | full read path | ratio |
|---:|---:|---:|---:|
| 0 | 281 B | 389 B | 1.38× |
| 4 | 1 320 B | 2 800 B | 2.12× |
| 8 | 2 312 B | 4 976 B | 2.15× |
| 16 | 4 392 B | 9 848 B | 2.24× |

Per header: **~260 B** in one pass, **~600 B** on the real path.

Fresh-JVM repeats, 16 headers: parser `4392 / 4392 / 4392`; read path `9848 / 9848 / 9856 / 9848`
— 0.08% spread, one run in four differing by a single 8-byte object. Allocation is a counting
measurement, so the run-to-run variance the perf discipline exists to catch does not arise at the
scale it exists to catch it. Stated rather than glossed: this is not evidence that a *throughput*
claim would be stable.

**Correction (review of the research PR).** The read-path figures above are the second set. The first
harness built a `new Http1Codec()` inside the measured loop, on the stated belief that the reader
gets a fresh codec per request. It does not: `CommunityHttpRequestProcessor.process` builds **one
codec per connection** and reuses it across the keep-alive/pipelined loop, calling `reset()` at the
top of each read. So a per-request figure is a steady-state figure, and charging every request for a
per-connection object inflated the read path by ~50 B — 9 904 where the honest number is 9 848. The
harness now mirrors the processor. **No conclusion moves**: the inflation is a constant, so it never
touched the per-header slope the findings rest on, and it is corrected here because a number that
gets quoted downstream should be the one production would produce.

## Finding

**A 16-header request allocates ~9.9 KB of heap to read ~500 B off the wire.** Roughly twenty times
the request's own size, per request, on the path the mission statement is about.

**The parser is not the whole story, and the plan's framing understates it.** T2-10 describes
`readAscii` materialising a `String` per token. Measured, that is a little under half the cost. The
rest is structural:

1. **The header block is parsed twice.** `CommunityHttp1RequestReader.tryParseRequest` runs
   `codec.parseHeaders(...)` for connection state and h2c detection, then
   `Http1RequestParser.parseHeaders(...)` again to build the `List<HttpHeader>`. Every token is
   materialised twice. The existing comment beside the second call acknowledges the two passes — it
   is deliberate about their *bounds* (ADR-071) and silent about their cost.
2. **Then the list is copied a third time** — `List.copyOf(headers)` after the `ArrayList` that the
   visitor filled.

That accounts for the ratio: ~260 B/header becomes ~520 B for the double pass, and the remaining
~80 B is the `HttpHeader` instance plus the list slot and its copy.

## The same shape elsewhere — swept, not assumed

Grepped the request and response paths for materialise-and-copy idioms and classified each by whether
it runs per request:

| site | per request? | shape |
|---|---|---|
| `Http1RequestParser.readAscii` / `trimOws` | yes, ×2 | `byte[]` + `String` per token, plus a substring |
| `CommunityHttp1RequestReader` | yes | `ArrayList` + `HttpHeader` per header + `List.copyOf` |
| `PendingRequestHeaders:64` | yes (h2) | `List.copyOf` per decoded request — same shape on the HTTP/2 path |
| `CommunityHttpResponseHeaders:84`, `InMemoryHttp2Exchange:85` | yes | a merged `ArrayList` per response |
| `HttpRouter:189` | yes | `path.substring` to strip the query |
| `CommunityHttpClientResponseDecoder:135-151, 217-218` | yes (client) | the **mirror** of the server defect: `toArray` + `new String` per token, `ArrayList`, two substrings and a `trim` per header, `List.copyOf` |
| `PathTemplate`, `StreamRouteTable`, `HttpRouter:63-65` | **no** — route-table construction | fine as they are |
| `Http1ResponseEncoder:27`, `CommunityHttpH2cUpgradeDetector:57` | **no** — `static final` constants | fine as they are |

So this is a **pattern across the HTTP subsystem**, not one method: materialise to `String`, collect
into an `ArrayList`, copy the list. Naming it that way matters, because a fix aimed only at
`readAscii` would leave more than half the measured cost in place.

## Verdict

Hypothesis **partly confirmed, and the framing corrected**. The allocation is real and large — ~20×
the wire bytes — which justifies the work. But token materialisation is under half of it; the double
parse and the list copies are the rest, and they are cheaper to remove.

## Next action

**Cheapest first, because it needs no new representation:** collapse the double parse. The codec's
pass and the list-building pass read the same bytes under the same bounds; one pass that both updates
connection state and yields the headers removes ~260 B per header with no API change and no
zero-copy machinery.

Only then is the zero-copy header representation worth designing, and it should be scoped to the
pattern above rather than to `readAscii` alone.

**Done (v0.12).** `Http1Codec.parseHeaders` took an optional `HeaderVisitor`, so connection state and
the header list come off one traversal; the list is handed on as a view rather than a copy. The
16-header request went **9 848 B → 5 784 B** on the collapsed parse alone (41%) and **→ 5 472 B** with
the copy dropped (44%), stable across three fresh JVMs. It also removed a hazard: with two passes the
enforced header limit depended on which reached it first unless both were handed identical bounds,
which is why `Http1Codec` no longer exposes its bounds at all (ADR-071, amendment of 2026-09-01).

**The rest of the sweep, closed (v0.12).** The RFC settled the representation question first, so the
four remaining sites could be taken without editing any of them twice. Each is listed with what it
actually turned out to be, because two of the five rows in the table above described the site
inaccurately.

| site | disposition |
|---|---|
| client response decoder | **done.** The mirror of the server defect, and the same fix: the read loop's framing probe stopped building a header list to read one field, and the decode stopped building `String`s per token. 7 624 → 1 952 B on an API JSON 200, 10 216 → 2 384 B on a page with cookies, 3 672 → 1 184 B on a 204. |
| `PendingRequestHeaders` (h2) | **done.** The decoded request now wraps the accumulator's list instead of copying it; the accumulator is built, filled and consumed inside one method, so nothing else can reach the list. 464 → 392 B for a 3-field request, 800 → 616 B for a 10-field one — the accumulator's own allocation, not the whole h2 decode, which HPACK dominates. |
| the router's query strip | **the row understated it.** The `substring` is real but conditional — a path with no query allocates nothing — and it was 128 B of a measured 1 168. The unconditional cost sitting beside it was `path.split("/", -1)`, run **twice per request**: once by the stream table and once by the template list, a third time on a HEAD fallback. Matching now walks the path in place. `GET /api/orders/42` went 1 040 → 320 B, a two-placeholder route 1 464 → 464 B, an exact-route hit 276 → ~44 B, and a route miss 528 → 0 B. |
| the response-header merge | **the claim did not hold.** `CommunityHttpResponseHeaders.merge` allocates one exactly-sized list and only when both sides are non-empty; an empty side returns the other list itself. There is nothing to remove without a concatenating view, which would trade one small allocation for an indirection on every `get()` in the write loop. The finding that survived inspection was a different one: `InMemoryHttp2Exchange` carried a byte-identical private copy of the method, and nothing covered either. The copy is gone and the survivor has tests. |

An honest note on the router figures: the residual on an exact-route hit is a `RouteMatch` record
that escape analysis sometimes scalar-replaces and sometimes does not, so that row reads 0–68 B
across fresh JVMs where the others are stable to a few percent. The route-miss row reaching exactly
0 B is what says the machinery itself can allocate nothing.

**Still open, and this is where the remaining cost is.** At ~345 B per header, token materialisation
dominates what is left of the read path. It is the one item the RFC deliberately did **not** promote:
the hard question is not the shape but the **lifetime** — header slices would point into a
`LoanedBuffer` that is recycled after the request, and `HttpRequest.headers()` is SPI. Two smaller
residues are recorded rather than fixed: the router strips the query twice per request, once in the
stream probe and once in `handle`, which needs a dispatcher contract change rather than a local edit;
and captured path parameters still go through a `HashMap` because `pathParams()` is typed
`Map<String, String>`, so the cheaper shapes stop at the SPI.

**Not measured here, and not claimed:** the CPU share. T2-10's own text says the allocation count is
known and its share of the 51 µs CPU/req is not — that remains true. Bytes are not microseconds, and
establishing the CPU half needs a full-stack run in `exeris-benchmarks`, which is where load
harnesses belong.
