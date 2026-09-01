# Research: what the HTTP/1 read path allocates per request

| | |
|---|---|
| **Branch** | `research/http1-header-allocation` |
| **Date** | 2026-09-01 |
| **Driver** | v0.12 T2-10 — *"the HTTP/1 header path materializes a `String` per token"* |
| **Status** | Measured. Gates the fix; the fix is not in this branch. |

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
| 0 | 281 B | 434 B | 1.54× |
| 4 | 1 320 B | 2 848 B | 2.16× |
| 8 | 2 312 B | 5 024 B | 2.17× |
| 16 | 4 392 B | 9 904 B | 2.26× |

Per header: **~260 B** in one pass, **~600 B** on the real path.

Fresh-JVM repeats, 16 headers: parser `4392 / 4392 / 4392`; read path `9904 / 9904 / 9896` — 0.08%
spread. Allocation is a counting measurement, so the run-to-run variance the perf discipline exists
to catch does not arise here. Stated rather than glossed: this is not evidence that a *throughput*
claim would be stable.

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

**Not measured here, and not claimed:** the CPU share. T2-10's own text says the allocation count is
known and its share of the 51 µs CPU/req is not — that remains true. Bytes are not microseconds, and
establishing the CPU half needs a full-stack run in `exeris-benchmarks`, which is where load
harnesses belong.
