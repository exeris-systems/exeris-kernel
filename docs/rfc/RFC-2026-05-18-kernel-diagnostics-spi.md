# RFC-2026-05-18: Kernel Diagnostics SPI for Agent and CLI Adapters

| Field             | Value                                                                                                |
|:------------------|:-----------------------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                                         |
| **Author(s)**     | Arkadiusz Przychocki                                                                                 |
| **Date Opened**   | 2026-05-18                                                                                           |
| **Date Closed**   | 2026-05-18                                                                                           |
| **Target ADR(s)** | [ADR-033](../adr/ADR-033-kernel-diagnostics-spi.md) (ACCEPTED 2026-05-18)                            |
| **Affected Repos**| `exeris-kernel`, `exeris-kernel-enterprise`, `exeris-ai-bridge` (cross-repo consumer of the surface) |
| **Reviewers**     | —                                                                                                    |

## Question

How should the Exeris kernel expose a **read-only, stable, low-frequency introspection surface** to out-of-process consumers (AI agents via `exeris-ai-bridge`, future CLIs, third-party monitoring) without breaching The Wall (ADR-006), without affecting hot-path zero-allocation contracts (per `docs/performance-contract.md`), and without dragging consumer-specific concerns into kernel SPI?

The answer is binary in shape: do we ship a **dedicated `KernelDiagnostics` SPI aggregator**, do we keep diagnostic readouts scattered across the existing provider surfaces (status quo), or do we lean entirely on the JFR event stream that already exists?

## Context

Two concrete forcing functions land this week:

1. **`exeris-ai-bridge` 0.4.0 is blocked.** [ADR-025](../../../exeris-docs/adr/ADR-025-ai-agent-bridge.md) was accepted 2026-05-15. The bridge ships three tool families — `docs:*`, `lsp:*`, `kernel:*`. The `kernel:*` family (`list_providers`, `list_capabilities`, `get_bootstrap_dag`, `describe_subsystem`) explicitly defers to a "`KernelDiagnostics` SPI RFC" called out in `ROADMAP.md` §0.4.0. Until that RFC lands, every `kernel:*` handler in the bridge returns `isError: true` with the message *"Not implemented yet — blocked on KernelDiagnostics SPI RFC."*
2. **Existing diagnostic points are usable but fragmented.** Today the kernel already exposes:
   - `SubsystemOrchestrator.subsystem(String name)` and `SubsystemOrchestrator.subsystems()` — public List/Optional returns of the live bootstrap DAG nodes.
   - `MemoryStats` value record (returned by `MemoryAllocator#stats()`).
   - `KernelEvent` records over `TelemetrySink` (JFR-first per ADR-005).
   - Each `Provider.name()` / `displayName()` (`TransportProvider`, `FlowProvider`, `KernelCryptoProvider`, …).
   - `SpiDiagnostics` utility class for safe formatting (`spi/util/SpiDiagnostics.java`).
   These are sufficient ingredients but lack a single aggregator contract. An external adapter that wants "current DAG + composed cap graph + per-subsystem status in one read" must hand-roll the join.

Cost of leaving the question unanswered:

- AI-bridge 0.4.0 ships indefinitely empty, blocking the whole agent-introspection story that ADR-025 promised.
- Each external consumer (bridge, future CLI, monitoring) reinvents the same aggregator, with no stability guarantee against the underlying provider surfaces.
- The current public methods on `SubsystemOrchestrator` are effectively a shadow SPI without a contract — they will drift.

Who is affected: agent-tooling consumers (bridge, third-party MCP clients), platform operators (any future `exeris-kernel-cli`), Studio (`exeris-platform`) when it eventually wants to render runtime state of a connected kernel, and the Enterprise tier (which wants to expose strictly more diagnostic data — io_uring queue depths, slab pool fragmentation, native pointer counts — without forking the contract).

## Investigation

### Prior art

- **OpenJDK `HotSpotDiagnosticMXBean`** — JMX-based, fragmentary, designed for VM ops not application introspection. Lessons: read-only by design, deliberately limited surface, deprecated overload pattern shows what *not* to do.
- **OTel SDK `Resource` + `MeterProvider.list()`** — similar shape but tied to OTel data model; rejected because the kernel must remain telemetry-vendor-agnostic.
- **Quarkus `RuntimeValue` introspection** — exposes bean graph at build-time, not runtime; not applicable.
- **`exeris-platform-lsp` custom methods (`exeris/listCapabilities`, `exeris/entityModel`)** — closest in-house prior art. Returns immutable records over JSON-RPC. Confirms the pattern of "narrow, schema-stable, append-only enum of methods."

### Constraints

| Constraint                              | Source                                       | Implication                                                                                                                                |
|:----------------------------------------|:---------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------|
| **The Wall**                            | ADR-006                                      | No Spring / DI / servlet types in the SPI signature. Plain Java records and interfaces only.                                               |
| **Zero-allocation hot-path discipline** | `docs/performance-contract.md`               | Diagnostic methods must be cold-path only. Calls per minute, not per request. Returns may allocate, calls must not be inside request loops. |
| **Open-core split**                     | ADR-008                                      | SPI surface is `public`. Enterprise overlay extends, never replaces, the Community implementation.                                          |
| **TCK obligation**                      | TCK-first review skill                       | New SPI ⇒ `AbstractKernelDiagnosticsTck` plus Community binding evidence; Enterprise must run the same TCK plus its overlay-specific cases. |
| **Append-only stability**               | ADR-024 (cap composition contract)           | Once shipped, the SPI grows by adding methods + records, never by changing signatures of existing ones. JSON schema is the wire contract.   |
| **Cross-repo readiness**                | `exeris-ai-bridge/ROADMAP.md` §0.4.0         | A child-process CLI (`exeris-kernel-diagnostics-cli`) must ship from `exeris-kernel-community` so the Node-side adapter can `spawn()` it.   |

### Data gathered

Existing public surfaces that any aggregator can compose from (counts as of `exeris-kernel` 0.8.0-SNAPSHOT):

- 9 `SubsystemProvider` types (Memory, Crypto, Persistence, Graph, Transport, Events, Flow, HTTP, Security).
- 1 orchestrator (`SubsystemOrchestrator`) with 2 public read methods.
- 1 stats record (`MemoryStats`) with composable shape per subsystem (other subsystems lack the equivalent; this is a gap the SPI would surface but not fill).
- ~40 `KernelEvent` codes in `spi/telemetry/` (event side, not state side).
- ~14 ADRs of kernel-internal architectural intent that describe the *shape* of what the SPI would return.

### Spike outcomes

No prototype built. The closest analogue is `SubsystemOrchestrator.subsystem(name)` being used internally by tests today; that pattern (named lookup → record return) is what the RFC is generalizing.

## Options Considered

### Option A: Unified `KernelDiagnostics` SPI aggregator

A dedicated SPI interface in `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/diagnostics/` exposing:

```java
public interface KernelDiagnostics {
    ProvidersSnapshot listProviders();
    CompositionSnapshot listCapabilities();
    BootstrapDagSnapshot getBootstrapDag();
    SubsystemSnapshot describeSubsystem(String name);
}
```

All returns are immutable records (`record ProvidersSnapshot(List<ProviderDescriptor> providers, Instant capturedAt) {}`, etc.) with stable JSON shape. The SPI is discovered via `ServiceLoader<KernelDiagnosticsProvider>`. Community ships `CommunityKernelDiagnosticsProvider` (priority 0) which reads from in-process `KernelBootstrap` state. Enterprise ships `EnterpriseKernelDiagnosticsProvider` (priority 100 per the open-core loading model, ADR-008) which **extends** the Community shape — same record types, additional Enterprise-only fields populated where available, never replaces semantics.

A tiny Java executable `exeris-kernel-diagnostics-cli` ships from `exeris-kernel-community` (Maven module inside the kernel repo). It reads framed JSON requests on stdin, writes responses on stdout, and is what `exeris-ai-bridge`'s Node-side `kernel-adapter.ts` `spawn()`s. Auth-free local mode (the kernel adapter trusts the spawning process). Remote / authenticated mode is explicitly out of scope and lands in `exeris-ai-bridge` 0.6+ (transport auth there, not here).

**Pros:**
- Single contract; consumers stop reinventing the join.
- Open-core extension model is clean — Enterprise overlay adds fields, Community handles missing-field gracefully (`Optional<>` typed accessors).
- Append-only stability story is concrete: new methods + new records, never altered signatures.
- TCK is well-shaped — `AbstractKernelDiagnosticsTck` mirrors the four-method surface plus snapshot-equality checks against a known fixture kernel.
- Aligns 1:1 with `exeris-ai-bridge` 0.4.0 ROADMAP — zero re-planning downstream.
- The Wall is preserved by construction — child-process boundary, JSON-over-stdio, no shared classloader.

**Cons:**
- New public SPI to maintain forever. Append-only is a real ongoing tax.
- Some duplication of intent vs. `KernelEvent` (event side) — consumers may ask for both and we must explain when to use which.
- Adds a CLI artifact to the kernel ship list (small executable, but extra release-engineering surface).

**Cost:** 1 SPI module addition (5–7 records + 1 interface) + 1 Community provider implementation (~150–300 LoC reading existing orchestrator state) + 1 CLI executable (~200 LoC: argument parsing, framed stdio, lifecycle) + TCK module + Enterprise overlay provider stub. Ballpark 2–3 sprints inside kernel, parallel with bridge 0.4.0 work.

### Option B: JFR-only — event-stream introspection

No new SPI. External consumers read the JFR event stream (already shipped, ADR-005) via `jdk.jfr.consumer.RecordingStream` against a running kernel's JFR file or in-process stream. State-of-the-world is reconstructed from event history.

**Pros:**
- Zero new SPI surface. Stability story is "JFR event schema is the contract", which is already a thing per ADR-005.
- Hot-path-free by construction — JFR events are already low-frequency lifecycle markers, not per-request.
- Naturally cross-repo — Enterprise already ships `BinaryGlassBoxSink` against the same `KernelEvent` records.

**Cons:**
- **Events ≠ state.** A consumer asking "what providers are currently composed?" must replay the boot DAG event-by-event to reconstruct the answer. This is fragile (missed events, ring-buffer wraparound, restart semantics) and shifts complexity to every consumer.
- No primary key for "current cap composition" — `KernelEvent` is event-flavored, not state-flavored. Adding state events just to make this work is the SPI in disguise, but worse.
- `exeris-ai-bridge` Node-side would need a JFR parser. JFR binary format support outside the JVM is thin (we own the spec via `exeris-telemetry-spec` but that wire format is the Enterprise Glass-Box stream, not JFR).

### Option C: Hybrid — state SPI plus JFR streaming

Ship Option A for **state queries** (cap graph, DAG, provider list) and use the existing `KernelEvent` / JFR stream for **event-flavored data** (subsystem state transitions, errors over time, crash forensics). The two surfaces stay deliberately disjoint: state is "what is composed right now", events are "what just happened".

**Pros:**
- Best fit for the actual ask: agents need state for "describe the system", events for "tail what just broke".
- Two surfaces, each narrower than a unified one — easier to keep append-only.
- Maps cleanly onto the existing `exeris-ai-bridge` planning split between 0.4.0 (state) and 0.7.0 (`bridge:health` style live diagnostics).

**Cons:**
- Two surfaces to maintain instead of one.
- A consumer doing "give me everything about subsystem X" needs to call both — concretely, `describeSubsystem("memory")` + filter JFR for `EX-MEM-*` events.

**Cost:** Option A cost + a documented contract that JFR is the second surface (no new SPI work there — leverages existing ADR-005 infrastructure). Effectively Option A near-term, with the second surface formalized in docs but already implemented.

### Option D (do nothing): keep diagnostic readouts on `SubsystemOrchestrator`

Mark `SubsystemOrchestrator.subsystems()` / `.subsystem(name)` as the public diagnostic surface, document it as such, and let consumers join in their own code. `MemoryStats`, `Provider.name()`, etc. stay as-is.

**Pros:**
- Zero new SPI cost. Ships today.
- Honest about what we currently have.

**Cons:**
- AI-bridge 0.4.0 stays blocked (or ships with a fragile in-house join).
- `SubsystemOrchestrator` is in `exeris-kernel-core`, not `exeris-kernel-spi` — the methods are "public" in Java visibility but they violate the SPI/Core boundary if treated as a contract for external consumers.
- Drift risk is real: any future refactor of the orchestrator silently breaks every external consumer.

## Recommendation

**Adopt Option A (unified `KernelDiagnostics` SPI aggregator) now, with the JFR-event surface formalized as the secondary, event-flavored channel — i.e. land on Option C in steady state, with Option A as the first deliverable.**

The deciding factors:

1. **The bridge is blocked today and explicitly planned against this exact shape.** ADR-025 §Engineering Protocol item 2 names `KernelDiagnostics` SPI as the dependency, and the bridge's `ROADMAP.md` §0.4.0 enumerates the four methods this RFC proposes. Picking any other option imposes re-planning cost on a downstream that has already committed.
2. **State and events are different questions and want different surfaces.** Option B's attempt to collapse them into one collapses badly — agents asking "what is composed?" want a snapshot, not an event replay. Conceding this up-front (Option C) is cheaper than retrofitting state on top of events later.
3. **The Enterprise overlay story is structurally cleanest with Option A.** Same record types, additional fields, Community fields gracefully `Optional<>`. The existing open-core loading model (ADR-008, priority=100 provider) drops in without invention.
4. **Status-quo cost is real.** `SubsystemOrchestrator.subsystems()` is already a shadow SPI used by tests; without Option A we either grandfather that pattern (Option D's downside) or refactor every test consumer when it inevitably moves.

### Why not the alternatives?

- **Option B (JFR-only)** — Reconstructing "current composition" from an event history is fragile and pushes the worst complexity onto every external consumer. JFR is the right channel for events, the wrong channel for state.
- **Option D (do nothing)** — Leaves ADR-025's promised `kernel:*` surface as permanent placeholder text, and treats `exeris-kernel-core` internals as if they were a public contract.

### Risks of the recommendation

- **Append-only discipline must hold.** Once the SPI ships, breaking changes are expensive. The TCK plus a JSON schema fixture in `exeris-kernel-tck` should catch most of this, but the discipline is real.
- **Enterprise overlay scope creep.** It will be tempting for Enterprise to expose `io_uring` queue depths, slab pool fragmentation, native pointer counts, etc. via diagnostic readouts that are mostly useful inside `exeris-enterprise-observability` already. The overlay should add Enterprise fields **only** where the agent / CLI use case is documented; otherwise the data stays in the JFR / forensics ring.
- **`exeris-kernel-diagnostics-cli` is a new release artifact.** Small, but real. Versioned in lockstep with the kernel.
- **The bridge will request more surface as it matures.** `bridge:health` (0.7.0 in the bridge ROADMAP) will want `KernelDiagnostics.health()` or similar. Acceptable as long as growth is append-only.
- **Snapshot atomicity is best-effort, not transactional.** A `listProviders()` + `getBootstrapDag()` pair may straddle a state transition. Documented in the SPI Javadoc; not worth solving with locking on the diagnostic path.

## Decision Record

| Field                  | Value                                                                                                                                                                |
|:-----------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Outcome**            | ACCEPTED                                                                                                                                                             |
| **Date**               | 2026-05-18                                                                                                                                                           |
| **Resulting ADR(s)**   | [ADR-033](../adr/ADR-033-kernel-diagnostics-spi.md) — Kernel Diagnostics SPI                                                                                         |
| **Notes**              | Recommendation adopted as-is. Option C steady-state explicitly endorsed: state stays with the SPI here, event streaming continues via JFR (Community) and the Enterprise Glass-Box binary stream over `exeris-telemetry-spec` consumed by `exeris-enterprise-observability`. Single CLI artefact in `exeris-kernel-community` (no separate Enterprise CLI) — the overlay rides through ServiceLoader priority, consistent with ADR-008. `schemaVersion` field confirmed required. Remaining open questions answered inside ADR-033 §The Decision (Obligations 1, 4, 5, 7, 8). |

## Open questions / follow-ups

- **SPI package location** — `eu.exeris.kernel.spi.diagnostics` or piggyback on `eu.exeris.kernel.spi.util`? Recommendation: new `diagnostics` subpackage, isolates the cold-path surface from the hot-path utility shelf. Owner: kernel-architect, target: in ADR-033.
- **CLI artifact coordinates** — `eu.exeris.kernel:exeris-kernel-diagnostics-cli`, shipped as a shaded jar with a `main` class. Wire into `exeris-kernel-community` reactor or its own module? Owner: kernel-build, target: in ADR-033.
- **JSON schema versioning** — embed a `schemaVersion` field in every top-level snapshot record (e.g. `"schemaVersion": "1.0"`). Owner: kernel-architect, target: in ADR-033.
- **Snapshot atomicity policy** — best-effort with a `capturedAt` `Instant` per snapshot; no kernel-side locking. Document in SPI Javadoc; revisit if a consumer demands stronger guarantees. Owner: kernel-architect, target: in ADR-033.
- **`describeSubsystem` enum vs. free-form name** — start with free-form `String name` (matches `SubsystemOrchestrator.subsystem(String)`); promote to a closed enum once subsystem boundaries stabilize at 1.0. Owner: kernel-architect, target: in ADR-033 (decision deferred to 1.0 if not earlier).
- **Health surface** — `bridge:health` / `KernelDiagnostics.health()` is out of scope for this RFC; `exeris-ai-bridge` 0.7.0 will trigger a follow-up RFC if it can't be expressed by composition of the four methods. Owner: ai-bridge maintainer, target: bridge 0.7.0 planning.
- **Enterprise overlay scope** — define explicitly which Enterprise fields are appropriate for diagnostic surface vs. forensics-only. Owner: kernel-enterprise architect, target: in the Enterprise mirror ADR-033 stub.
