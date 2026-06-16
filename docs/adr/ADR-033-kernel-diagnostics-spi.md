# ADR-033: `KernelDiagnostics` SPI — Read-Only Runtime Introspection for Agent and CLI Adapters

**Status:** Accepted (decision-only — SPI implementation deferred to v0.9)
**Date:** 2026-05-18
**Owner:** kernel/diagnostics

> **Implementation rollout:** This ADR records the *decision* only. No code ships with it in v0.8.0 — the `eu.exeris.kernel.spi.diagnostics` package, the `CommunityKernelDiagnosticsProvider`, the `AbstractKernelDiagnosticsTck`, and the `exeris-kernel-diagnostics-cli` artefact all land in **v0.9** (tracked in `docs/ROADMAP.md`). The contract below is accepted as the target shape; treat it as design intent until the v0.9 implementation PR lands.
**Visibility:** public
**Scope:** cross-repo (kernel SPI + Community implementation + Community CLI artefact; Enterprise overlay; AI-bridge consumer)
**Authors:** Arkadiusz Przychocki
**Driven By:** [RFC-2026-05-18 Kernel Diagnostics SPI](../rfc/RFC-2026-05-18-kernel-diagnostics-spi.md) (ACCEPTED 2026-05-18)
**Cross-references:** ADR-005 (JFR-first telemetry), ADR-006 (Spring-Free Kernel Boundary — "The Wall"), ADR-007 (Next-Gen Runtime Architecture), ADR-008 (Open-Core Strategy), ADR-018 (Observability Tooling Repo Split), ADR-020 (Open-Core Documentation Boundary), ADR-024 (Capability Composition Model), ADR-025 (AI Agent Bridge), [ADR-039](ADR-039-open-core-observability-boundary.md) (Open-Core Observability Boundary — the public/open-core anchor for the state/event split)

## Context

`exeris-ai-bridge` 0.4.0 is blocked: ADR-025 named a `KernelDiagnostics` SPI as the dependency for the `kernel:*` tool family (`list_providers`, `list_capabilities`, `get_bootstrap_dag`, `describe_subsystem`); the bridge's `ROADMAP.md` enumerates the four methods. Today every `kernel:*` handler in the bridge returns `isError: true` — *"Not implemented yet — blocked on KernelDiagnostics SPI RFC."*

The kernel already exposes the *ingredients* of an answer: `SubsystemOrchestrator.subsystems()` / `.subsystem(name)`, `MemoryAllocator#stats() → MemoryStats`, each `Provider.name()` / `displayName()`, and the JFR `KernelEvent` stream (ADR-005). What is missing is a single, contract-stable, cold-path aggregator that an out-of-process consumer can rely on. Treating the existing `SubsystemOrchestrator` public methods as a shadow SPI breaks the SPI/Core boundary (the methods live in `exeris-kernel-core`, not `exeris-kernel-spi`) and silently drifts on every orchestrator refactor.

State and events are different questions: an agent asking "what is composed right now?" wants a snapshot, not an event replay. RFC-2026-05-18 considered four options (unified SPI; JFR-only; hybrid state-SPI + JFR-event; do-nothing) and recommended a unified SPI now, with the JFR stream formalised as the secondary, event-flavoured surface (Enterprise's `BinaryGlassBoxSink` over `exeris-telemetry-spec` already covers that side end-to-end).

This ADR locks the decision and the obligations.

## 🏁 The Decision

**Ship a `KernelDiagnostics` SPI in `eu.exeris.kernel.spi.diagnostics`, with a Community provider in `exeris-kernel-community`, a single small CLI artefact `exeris-kernel-diagnostics-cli` as a top-level module in the root kernel reactor (sibling of `exeris-kernel-community`) that any out-of-process consumer can `spawn()`, and an Enterprise overlay provider in `exeris-kernel-enterprise` that extends — never replaces — the Community shape. State is owned by this SPI; events stay with `KernelEvent` / JFR / `exeris-telemetry-spec` and are not duplicated here.**

The SPI is the **only** public contract for reading kernel state out-of-process. Internal callers (tests, orchestrator) keep using `SubsystemOrchestrator` directly; external consumers (ai-bridge, future CLIs, Studio when it gains remote-kernel introspection) go through `KernelDiagnostics`.

### Interface surface

```java
package eu.exeris.kernel.spi.diagnostics;

public interface KernelDiagnostics {

    ProvidersSnapshot     listProviders();
    CompositionSnapshot   listCapabilities();
    BootstrapDagSnapshot  getBootstrapDag();
    SubsystemSnapshot     describeSubsystem(String name);
}

public interface KernelDiagnosticsProvider {
    KernelDiagnostics create();
    int priority();           // open-core loading model (ADR-008): Community = 0, Enterprise = 100
    String providerName();    // stable, used in JFR events / diagnostics
}
```

Each top-level snapshot record carries a `schemaVersion` string field and an `Instant capturedAt` timestamp; nested fields use `Optional<>` where the data may legitimately be absent (Community on Enterprise-only fields, subsystem not yet `READY`, etc.).

### Concrete obligations

1. **SPI package.** All public types live in `eu.exeris.kernel.spi.diagnostics.*`. Snapshot records (`ProvidersSnapshot`, `CompositionSnapshot`, `BootstrapDagSnapshot`, `SubsystemSnapshot`, plus their nested descriptors) are sealed where the kernel knows the exhaustive set; otherwise plain `record`. No type in this package may import from `exeris-kernel-core`, `org.springframework.*`, `io.netty.*`, `reactor.*`, `jakarta.servlet.*`, or any host-runtime-specific package.
2. **Cold-path discipline.** `KernelDiagnostics` methods MUST NOT be invoked from the request hot path. Allocation is permitted on calls (`record` instantiation, defensive `List.copyOf(...)`, `Instant.now()`); call frequency is "per minute, not per request." This is documented in Javadoc on every method and enforced by code review, not by runtime gate.
3. **Provider discovery.** `KernelDiagnosticsProvider` is loaded via `ServiceLoader<KernelDiagnosticsProvider>` exactly like other kernel providers (ADR-007 §provider discovery). On classpath collision, the highest `priority()` wins. Community provider in `exeris-kernel-community` declares `priority() = 0`; Enterprise provider in `exeris-kernel-enterprise` declares `priority() = 100`. No other priorities are reserved; third-party providers may use any value but are not expected to outrank the Enterprise overlay. On a `priority()` tie, the winner is the first provider in `ServiceLoader` iteration order — implementation-defined and not guaranteed stable across runs, so providers MUST NOT rely on winning a tie.
4. **Single CLI artefact.** A single executable JAR `eu.exeris.kernel:exeris-kernel-diagnostics-cli` ships from the root kernel reactor as a separate top-level Maven module (sibling of `exeris-kernel-community`). No separate `-enterprise-cli` artefact exists. When the Enterprise overlay jar is on the classpath, the same CLI binary picks up the Enterprise provider via ServiceLoader and exposes the additional Enterprise fields transparently. The CLI reads framed JSON requests on stdin, writes JSON responses on stdout, has no network surface, and trusts the spawning process (auth-free local mode). Authenticated / remote variants are explicitly future work — out of scope for ADR-033.
5. **Versioning — JSON wire schema and Java interface evolve under different rules.**
   - *JSON snapshot records (wire contract).* Every top-level record serialises with a `schemaVersion` field as the first key; v1.0 is the initial published schema. Changes are **append-only**: adding a field is a minor bump (`schemaVersion: "1.1"`); removing or repurposing an existing field is forbidden inside major version 1.x and requires `schemaVersion: "2.0"` plus a deprecation window. The schema fixture lives in `exeris-kernel-tck` and is asserted on every CI run.
   - *Java `KernelDiagnostics` interface (binary contract).* Adding a method is **not** a `schemaVersion` minor bump — it is a binary-breaking change for every `KernelDiagnosticsProvider` implementation. It requires updating all known providers (Community, Enterprise) in lockstep plus a compatibility story: a `default` method wherever a no-op / `Optional.empty()` answer is meaningful, and a matching `AbstractKernelDiagnosticsTck` extension. Pre-1.0 this is in scope per the kernel's pre-GA SPI-evolution stance; post-1.0 GA it is governed by the kernel binary-compatibility policy, not by `schemaVersion`.
6. **Open-core extension contract.** The Enterprise overlay provider returns the **same record types** as the Community provider. It populates Enterprise-only fields (`io_uring` queue depths, slab pool fragmentation, native pointer counts, etc.) where the agent / CLI use case is documented; otherwise such data remains in the JFR / `exeris-telemetry-spec` Glass-Box binary stream consumed by `exeris-enterprise-observability`. Enterprise MUST NOT add fields to the public records that Community cannot at least represent as `Optional.empty()`. Fork of the record types is forbidden.
7. **Snapshot atomicity is best-effort.** Each method captures its own `capturedAt: Instant`; a `listProviders()` + `getBootstrapDag()` pair MAY straddle a state transition. The Javadoc documents this; the SPI does not introduce kernel-side locking to "fix" it. Consumers that need a consistent multi-snapshot view rebuild it from event history (JFR side).
8. **Subsystem-name shape.** `describeSubsystem(String name)` takes a free-form `String` (matches `SubsystemOrchestrator.subsystem(String)` 1:1). Promotion to a closed enum is deferred to kernel 1.0 GA, when the set of subsystems is locked. The Javadoc names today's exhaustive set: `memory`, `crypto`, `persistence`, `graph`, `transport`, `events`, `flow`, `http`, `security`.
9. **TCK obligation.** `AbstractKernelDiagnosticsTck` ships in `exeris-kernel-tck` with the SPI. It exercises the five-method surface against a known-fixture kernel (records returned shape, `schemaVersion`, `Optional<>` field semantics, snapshot non-atomicity acknowledged). *(Amended v0.9: four-method → five-method when `getJvmErgonomics() → RuntimeErgonomicsSnapshot` was added. Per Obligation 5 the new method is a binary-breaking interface change, so it ships as a `default` returning `RuntimeErgonomicsSnapshot.unknown()` — all known providers updated in lockstep, the TCK gaining the well-formedness / `Optional.empty()`-degradation / `default`-method-compat cases. The wire schema grew append-only — a new top-level record, `schemaVersion` held at `1.0` since v0.9.0 ships the whole surface as the first published schema.)* Community provider in `exeris-kernel-community-testkit` runs the TCK in CI; Enterprise provider runs the same TCK plus overlay-specific cases in `exeris-kernel-enterprise` CI (ADR-008 open-core loading symmetry).
10. **No event surface here.** `KernelDiagnostics` does not expose any "tail events" / "subscribe" / "watch" method. Live event streaming consumers go through JFR (Community) or the Enterprise Glass-Box binary stream over `exeris-telemetry-spec` consumed by `exeris-enterprise-observability` (ADR-018). This is the hybrid model from RFC-2026-05-18 §Option C, locked in.

> **v0.9 implementation guardrail (Obligation 10, structural enforcement).** The state/event separation
> must be enforced **structurally**, not just in prose: an ArchUnit / boundary rule that the
> `eu.exeris.kernel.spi.diagnostics.*` package MUST NOT import `eu.exeris.telemetry.spec.*`, JFR
> `@Event` / `jdk.jfr.Event` types, or any frame / `rawArgs` type. The rule lands **with** the Sprint 1
> SPI code (the package does not exist yet, so the rule cannot land earlier). It is added as a new
> `@ArchTest` rule to the existing **live** ArchUnit suite `ExerisArchitectureTest`
> (`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/arch/`), which already enforces the SPI-purity
> rules (`noImplLeaksInSpi`, `noThreadLocal`, `noUnsafe`, …) and runs in CI on JDK 26 GA — no separate
> harness is needed.

## Consequences

### ✅ Positive Outcomes

- **[+] AI-bridge 0.4.0 unblocks.** Bridge `kernel-adapter.ts` `spawn()`s the CLI, every `kernel:*` tool resolves against the SPI. ADR-025's promised surface materialises.
- **[+] One contract, not five shadow APIs.** External consumers stop reinventing the join over `SubsystemOrchestrator` / `MemoryStats` / `Provider.name()`. The SPI/Core boundary stays honest.
- **[+] Open-core overlay is structurally clean.** Same records, same CLI, more data when Enterprise is present. This is exactly the open-core promise of ADR-008.
- **[+] Stability story is concrete.** `schemaVersion` + TCK fixture + append-only discipline gives consumers a real semver contract for the JSON shape.
- **[+] State / event separation is now official.** State is here; events stay in JFR / `exeris-telemetry-spec`. No duplication, no overlap.

### ⚠️ Trade-offs

- **[-] New public SPI to maintain forever.** Append-only is a real ongoing tax. Future agent demands (health, watch, write) will exert pressure; the discipline must hold.
- **[-] New release artefact.** `exeris-kernel-diagnostics-cli` is a small shaded jar but it is a real binary, versioned in lockstep with the kernel.
- **[-] Snapshot non-atomicity is exposed to consumers.** Documented, not solved. Agents that need transactional views must reconstruct them from JFR.
- **[-] Enterprise overlay-scope discipline is judgment-driven.** "Is this field agent-useful or forensics-only?" is not mechanically decidable; the obligation is to keep forensics-only data in `exeris-enterprise-observability` rather than leak it into the diagnostic SPI.

### 📋 What is NOT in scope

- **Authenticated / remote diagnostic transport.** The CLI is auth-free local-spawn. Networked variants land separately (likely `exeris-ai-bridge` 0.6+ transport auth, not here).
- **Mutation surface.** `KernelDiagnostics` is read-only. No `setX(...)`, no `reload()`, no `drainSubsystem()` — those belong on a future operator-control SPI if they exist at all.
- **`bridge:health` style derived/synthetic checks.** Aggregator surfaces that combine SPI + LSP + JFR live in `exeris-ai-bridge`, not in the kernel SPI.
- **JFR event-stream consumption.** Events stay where they are (Community: JFR; Enterprise: Glass-Box binary stream over `exeris-telemetry-spec`). No "watch" or "subscribe" method on `KernelDiagnostics`.
- **Studio-side rendering.** `exeris-platform` may eventually consume the bridge's `kernel:*` surface to render a connected kernel's state — that integration is platform-side, not kernel-side.
- **Schema-versioning across major bumps.** v1.x is append-only; the v2.0 migration story is deliberately deferred until a forcing function exists.

## Cross-references

- **ADR-005** (JFR-First Telemetry Strategy) — the event side this ADR explicitly does not duplicate.
- **ADR-006** (Spring-Free Kernel Boundary — "The Wall") — the SPI package must not import any host-runtime-specific type; the CLI is a separate process by construction, preserving The Wall.
- **ADR-007** (Next-Gen Runtime Architecture) — ServiceLoader-based provider discovery is the model this ADR reuses.
- **ADR-008** (Open-Core Strategy & Commoditization of Off-Heap TLS) — the priority=0 / priority=100 provider overlay pattern is reused verbatim.
- **ADR-018** (Observability Tooling Repo Split) — confirms the event-side tooling lives in `exeris-enterprise-observability`, not in the kernel diagnostic SPI.
- **ADR-024** (Capability Composition Model — `@Provides` / `@Requires` / Build-Time Validation) — `listCapabilities()` returns the resolved composition graph for the running kernel; the record shapes mirror the ADR-024 model.
- **ADR-025** (AI Agent Bridge — MCP Server for Ecosystem Introspection) — the consumer whose unblocking motivated this ADR.
- **[RFC-2026-05-18](../rfc/RFC-2026-05-18-kernel-diagnostics-spi.md)** — the option analysis behind this decision.
- **`exeris-ai-bridge/ROADMAP.md` §0.4.0** — the consumer roadmap that this ADR aligns 1:1 with.
- **`exeris-telemetry-spec` v1.0.0** — wire format for the Enterprise event side; not used by this ADR but named to make the state/event split explicit.

## Engineering Protocol

1. **SPI module addition.** A new Maven module `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/diagnostics/` lands in the same change set as this ADR's first implementation PR. Module is part of the existing `exeris-kernel-spi` reactor artefact, not a separate jar.
2. **TCK module.** `exeris-kernel-tck` gains `AbstractKernelDiagnosticsTck` and a JSON schema fixture asserted by every Community and Enterprise CI run.
3. **Community provider.** `exeris-kernel-community` ships `CommunityKernelDiagnosticsProvider` (priority 0) reading from `KernelBootstrap` / `SubsystemOrchestrator` state. `META-INF/services/eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider` registers it.
4. **CLI artefact.** New top-level module `exeris-kernel-diagnostics-cli` in the root kernel reactor (sibling of `exeris-kernel-community`), shaded executable JAR with stable `main` class `eu.exeris.kernel.diagnostics.cli.DiagnosticsCli`. Released in lockstep with the kernel.
5. **Enterprise overlay.** `exeris-kernel-enterprise` ships `EnterpriseKernelDiagnosticsProvider` (priority 100) returning the same record types with Enterprise-only fields populated where appropriate per Obligation 6. Mirror ADR (cross-repo) with the link stub convention of ADR-020.
6. **Consumer integration.** `exeris-ai-bridge` 0.4.0 implements `src/transport/kernel-adapter.ts` against the CLI's stdio JSON protocol; this ADR is the binding contract that bridge tests assert against. Bridge cross-repo link stub: `exeris-ai-bridge/docs/adr/ADR-033.link.md`.
7. **Migration owner & target window.** Kernel SPI + Community provider + CLI: kernel-architect, v0.9 (next minor after current 0.8 sprint). TCK: same window. Enterprise overlay: enterprise-architect, v0.6 of `exeris-kernel-enterprise` (next minor after current 0.5). Bridge consumer: ai-bridge maintainer, 0.4.0.
8. **JFR event emission for diagnostic calls themselves.** Each `KernelDiagnostics` method emits a single JFR `KernelEvent` at INFO level on call (code `EX-DIAG-1001` through `EX-DIAG-1005`), so operators can audit out-of-process introspection. This is **not** a zero-allocation path — the JFR event object and its string fields allocate; Obligation 2 explicitly permits that. The cold-path call frequency ("per minute, not per request" per Obligation 2) is what makes the allocation acceptable: it never touches a request hot path or its allocation budget. *(Amended v0.9: range extended `..1004` → `..1005` when `getJvmErgonomics()` joined the surface — see Obligation 9.)*
