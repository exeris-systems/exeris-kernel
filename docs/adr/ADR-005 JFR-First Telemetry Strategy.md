# ADR-005: JFR-First Telemetry Strategy

| Atrybut         | Wartość                                                                                                    |
|:----------------|:-----------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted 2026-05-08; decision date 2026-02-22)                                                |
| **Deciders**    | Arkadiusz Przychocki                                                                                       |
| **Date**        | 2026-02-22                                                                                                 |
| **Scope**       | kernel/observability                                                                                       |
| **Owning Repo** | `exeris-kernel`                                                                                            |
| **Driven By**   | ADR-007 update (2026-02-22) — Next-Gen Runtime Architecture refinement                                     |
| **Compliance**  | [Strategic Pillar: No-Waste Compute](../whitepaper.md), [Performance Contract](../performance-contract.md) |

## Context and Problem Statement

The kernel needs first-class observability — bootstrap order, allocation events, transport binds, crypto failures, lifecycle transitions, security denials — without violating the No Waste Compute contract on the hot path.

Conventional Java observability stacks fail this test in three different ways:

1. **String-based logging frameworks (SLF4J/Logback/Log4j2)** allocate `String` and formatter objects on every emit. At 1M+ QPS this generates GC pressure that swamps the actual workload. Async appenders shift the cost to a separate thread but do not eliminate it; the producer side still allocates.
2. **Java agent–based instrumentation (OpenTelemetry agent, etc.)** injects bytecode that adds carrier-pinning hazards near `synchronized` blocks (pre-JDK 24) and instruments method entry/exit on hot paths regardless of whether the path needs the data.
3. **Sidecar / out-of-process tracers** require IPC on the hot path. The serialisation, copy, and crossover-syscall costs are exactly the kind of waste this platform exists to eliminate.

We need a telemetry mechanism that is:
- **Allocation-free** on the steady-state hot path.
- **In-process** — no IPC, no agent injection, no sidecar.
- **Strongly typed** — events have schemas, not arbitrary key/value bags.
- **Toggleable at runtime** with zero residual cost when disabled.
- **Native to the JVM** — no third-party agent runtime risk.

JFR (Java Flight Recorder) satisfies all five.

## 🏁 The Decision

**Every kernel subsystem emits its lifecycle, failure, and contract-observability data as strongly-typed JFR events.** External observability stacks (Prometheus, OTLP) bridge from JFR; they never instrument the kernel directly.

**Concrete obligations:**

1. **Strongly-typed events.** Each subsystem defines `*Event extends jdk.jfr.Event` classes. Events carry typed fields (`@Label`, `@Description`, `@Timespan`, `@Threshold`) — never `Map<String, Object>`.
2. **`@StackTrace(false)` on hot paths.** Stack capture costs ~10 µs per emission and triggers JFR-internal allocation. Hot-path events (every TLS wrap/unwrap, every transport submit, every persistence statement bind) MUST set `@StackTrace(false)`. Lifecycle/failure events on cold paths may set `@StackTrace(true)`.
3. **Lightweight payloads.** Event field count is bounded; no large `String` materialisation, no `byte[]` copies into event fields. Secret-bearing fields use the `EX-*` taxonomy with `rawArgs` discipline (per ADR-012).
4. **Subsystem coverage.** Bootstrap (DAG progress + state transitions), Memory (slab alloc/free, allocator selection), Crypto (TLS handshake, BIO lifecycle, `EVP_*` errors), Transport (bind, submit batch, CQ poll), Persistence (acquire, statement bind, query exec, release), Security (deny, role check, isolation routing), Flow/Saga (state transitions, retries) — all instrumented.
5. **Cross-service bridging via ADR-018.** When the platform needs cross-process observability (e.g., distributed tracing, multi-node JFR aggregation), the wire format lives in `exeris-telemetry-spec` and is consumed by `exeris-enterprise-observability` (Repo B in the ADR-018 split). The kernel emits JFR locally; the wire-format adapter is enterprise-tier.

**What this ADR does NOT mandate:**

- It does not ban SLF4J for non-hot-path logging. Build tooling, bootstrap diagnostics, and CLI output may use SLF4J.
- It does not require operators to consume JFR directly. JConsole, JMC, `jfr` CLI, and `exeris-enterprise-observability` are all valid consumers.
- It does not preclude future bridges to OpenTelemetry. Such a bridge is enterprise-tier and reads from JFR — it does not displace JFR.

## Consequences

### ✅ Positive Outcomes

- **[+] Zero-allocation hot paths preserved.** TLS wrap/unwrap, transport CQ poll, slab alloc/free, persistence bind — all emit JFR with zero `eu.exeris.*` heap allocations on the steady-state path.
- **[+] Native to the JVM.** No agent runtime, no third-party library on the hot path, no transitive CVE surface from instrumentation libraries.
- **[+] Strongly typed.** Schema lives in code (`*Event` class). Field renames are compile errors. Refactoring is safe.
- **[+] Toggleable.** JFR can be enabled/disabled per-event at runtime via `jcmd JFR.configure` or recording configs. Disabled events have a single volatile-read cost.
- **[+] Operationally familiar.** Java operators already know `jfr`, JMC, and JFR streaming.

### ⚠️ Trade-offs

- **[-] JFR-aware tooling required.** Operators expecting Prometheus scrape endpoints out of the box need to either run `exeris-enterprise-observability` (the Repo B JFR-to-OTLP bridge) or invest in JFR tooling. Docs must make this explicit.
- **[-] Each new event is code.** Defining a typed `*Event` class is more boilerplate than `log.info("...")`. The trade-off is intentional — code review catches schema drift that string templates hide.
- **[-] JFR storage cost.** Long recordings produce non-trivial `.jfr` files. Operators must size disk and rotate recordings; defaults should err toward short rolling buffers.

### 📋 Conventions

- **Event naming:** `<Subsystem><Operation>Event` (e.g., `BootstrapPhaseCompletedEvent`, `IoUringSqeSubmittedEvent`, `CryptoTlsHandshakeFailedEvent`).
- **Categories:** Use `@Category({"exeris", "<subsystem>"})` so JFR consumers can filter cleanly.
- **Thresholds:** Hot-path events that occur frequently set a default `@Threshold` (e.g., 100 µs) so steady-state recordings stay manageable.
- **Lifecycle:** Lifecycle events (start/stop/state-change) are unconditional. Hot-path events are threshold-gated.

## Cross-references

- ADR-007 (Next-Gen Runtime Architecture) — drove this decision; the runtime architecture refresh on 2026-02-22 codified JFR as the in-process telemetry channel.
- ADR-012 (Security Trust Model) — defines the `EX-*` error taxonomy and `rawArgs` secret-safety discipline that JFR payloads inherit.
- ADR-018 (Observability Tooling Repo Split) — defines how Enterprise observability (Repo B) consumes JFR and bridges to external systems via the `exeris-telemetry-spec` wire format (Repo C).
- `docs/subsystems/telemetry.md` — operational guidance for adding new events.
- `docs/performance-contract.md` — quantifies the hot-path allocation budget that this ADR's payload-discipline rules protect.

## Engineering Protocol

Once this decision is ACCEPTED, every kernel subsystem's lifecycle/failure points must be reachable through a typed `*Event` class. Existing subsystems already comply; this ADR codifies the existing reality and locks the discipline against future drift.
