# Kernel Subsystem: Telemetry (L1 Observability)

**Physical Layout:**

- SPI (planned, not yet implemented in this repo): `eu.exeris.kernel.spi.telemetry.*` (`TelemetryRouter`, `TelemetrySink`, `TelemetryEvent`)
- Core: `eu.exeris.kernel.core.telemetry.*` (`JfrTelemetrySink`, `BinaryGlassBox`, `GlassBoxSerializer`)
- Enterprise: Binary deterministic sink, structured JFR streaming over off-heap ring buffer

**Layer:** L1 (Observability)  
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Telemetry subsystem** is the **Zero-Allocation Observability Pipeline** for the Exeris Kernel.
It is designed around a single invariant: **no `String` concatenation and no heap allocation may occur
on the event-emission hot-path**.

The core mechanism is the **Glass-Box Observability Pattern**: every `ExerisKernelException` subclass
carries a `rawArgs: Object[]` payload that encodes domain context as **raw primitives** (`long`, `int`,
`Enum`). These are serialised directly into a deterministic binary frame — bypassing `toString()`,
`StringBuilder`, and `Formatter` entirely.

> **Why "Glass-Box"?** Traditional observability hides system state behind gigabytes of string-formatted
> logs where GC pauses distort timings and `StringBuilder` allocations corrupt the failure path.
> Exeris builds systems from glass and steel: every allocation, every `rawArg`, every byte entering the
> ring buffer is **visible, deterministic, and measurable in nanoseconds**. Nothing is hidden under the
> Garbage Collector's carpet.

---

## Design Principles

| Principle                          | Implementation                                                                     |
|:-----------------------------------|:-----------------------------------------------------------------------------------|
| Zero string formatting on hot-path | `rawArgs[]` are never concatenated inside `ExerisKernelException`                  |
| JFR-First                          | Every critical lifecycle event emits a typed `@jdk.jfr.Event` subclass             |
| Pluggable sinks                    | `TelemetrySink` SPI — Community gets JFR/SLF4J, Enterprise gets binary ring-buffer |
| Async dispatch                     | `TelemetryRouter` dispatches to sinks off the caller's critical path               |
| Structured error codes             | `EX-[DOMAIN]-[ID]` format enables log-scraper pattern matching without regex       |

---

## The Glass-Box Observability Pattern

### Safety Contract (Non-Negotiable)

`String.formatted()`, `String.valueOf()`, and `StringBuilder` are **strictly banned** inside
`ExerisKernelException` constructors. This is not a style preference — it is a **Safety Contract**.
When the system is under memory pressure (the most likely moment to throw `MemoryExhaustedException`),
a `StringBuilder` allocation on the exception-construction path would add GC pressure to an already
stressed allocator. The failure path must never worsen the failure.

### The Problem (Legacy "Muddy Water" Pattern)

```java
// ❌ BANNED — allocates StringBuilder + String on every exception construction:
throw new RuntimeException(
    "Memory exhausted: requested %d bytes, available %d bytes"
            .formatted(requestedBytes, availableBytes));
```

### Solution: rawArgs Binary Layout

```java
// ✅ CORRECT — zero String allocation. rawArgs stored as primitives:
public MemoryExhaustedException(long requestedBytes, long availableBytes) {
    super(KernelErrorCodes.EX_MEM_1001, "Off-heap allocator exhausted", null,
            requestedBytes, availableBytes);
}
```

The `Object[]` varargs boxing (autobox of `long` → `Long`) is the only permitted allocation
per throw, because exceptions are **never thrown on the allocation hot-path** — they represent
exceptional failure states, not normal operation.

### Binary Struct Mapping (Enterprise Glass-Box)

The Enterprise `GlassBoxSerializer` maps `rawArgs` to a fixed-width binary struct written
directly into an off-heap ring buffer via `MemorySegment.set(ValueLayout, offset, value)`:

```
Offset  Size  Type    Field
──────  ────  ──────  ─────────────────────────────────
0       8     long    timestamp_nanos
8       16    UUID    traceId (two longs: msb + lsb)
24      2     short   errorCode_domain  (EX_MEM → 1, EX_NET → 4, ...)
26      2     short   errorCode_id      (1001, 1002, ...)
28      N     varies  rawArgs payload (layout defined per error code)
```

No `toString()`, no `ObjectOutputStream`, no JSON serialiser is ever invoked.
Reading side (crash-log analyser or APM agent) interprets the binary struct directly.

---

## Error Code Registry

Every `ExerisKernelException` subclass MUST declare its `rawArgs` binary layout in a Javadoc comment
**and** register its error code in `KernelErrorCodes`. The table below is the human-readable index —
`KernelErrorCodes.java` is the source of truth for the binary decoder.

```
EX-MEM-1001  Off-heap exhausted             rawArgs[0]=long requestedBytes,    [1]=long availableBytes
EX-MEM-1002  Arena leak detected            rawArgs[0]=long segmentAddress,     [1]=long segmentByteSize
EX-MEM-1003  AllocationHint conflict        (no rawArgs)
EX-BOOT-0001 DAG cycle detected             rawArgs[0]=String[] cycleMembers    ← emitted by orchestrator (pre-telemetry panic)
EX-BOOT-0002 Bootstrap failure              rawArgs — opaque (variable arity per pathway; Glass-Box consumers MUST NOT rely on layout)
EX-BOOT-0003 Bootstrap deadline exceeded    rawArgs[0]=String subsystemName,    [1]=long deadlineMs
EX-BOOT-0004 Memory provider bootstrap      rawArgs[0]=String providerName,     [1]=long requestedBytes
EX-BOOT-3001 Telemetry provider failure     rawArgs[0]=String providerName,     [1]=String reason
EX-NET-2001  TLS operation failure          rawArgs[0]=int nativeErrorCode,     [1]=String detail
EX-NET-2002  Crypto provider bootstrap      rawArgs[0]=String providerName,     [1]=String reason
EX-NET-4001  Transport bind/handshake       rawArgs[0]=String transportName,    [1]=int port
EX-NET-4002  Transport send failure         rawArgs[0]=String transportName,    [1]=long bytesSent
EX-NET-4003  Transport receive timeout      rawArgs[0]=String transportName,    [1]=long timeoutMs
EX-NET-4004  Transport engine bootstrap     rawArgs[0]=String transportName,    [1]=String reason
EX-NET-4005  Transport engine start         rawArgs[0]=String transportName,    [1]=int port
EX-NET-4006  PAQS load shedding             rawArgs[0]=String transportName,    [1]=int streamPriority,    [2]=int thresholdPriority
EX-NET-4007  Buffer exhaustion              rawArgs[0]=String transportName,    [1]=int poolCapacity,      [2]=int activeSlabs
EX-SEC-2001  PrincipalContext missing        (no rawArgs)
EX-SEC-2002  Token validation failure       rawArgs[0]=String tokenType,        [1]=String failureReason
EX-RUN-3002  Carrier pinned                 rawArgs[0]=long blockTimeMs,        [1]=String carrierName
EX-EVENT-6001 Generic event failure         rawArgs[0]=String message
EX-EVENT-6002 Bus publish failure           rawArgs[0]=String eventType,        [1]=long queueDepth,        [2]=long queueCapacity
EX-EVENT-6003 Registry conflict             rawArgs[0]=String eventType,        [1]=int ordinal
EX-EVENT-6004 Provider boot failure         rawArgs[0]=String providerName,     [1]=String reason
EX-FLOW-7001 Provider boot failure          rawArgs[0]=String providerName,     [1]=String reason
EX-FLOW-7002 Lifecycle / schedule fail      rawArgs[0]=String engineName,       [1]=String phase,           [2]=String staticReasonCode, [3]=int contextVal
EX-FLOW-7003 Step execution failure         rawArgs[0]=String defName,          [1]=long idMost,            [2]=long idLeast,            [3]=int stepIdx, [4]=String reason, [5]=String causeType
EX-FLOW-7004 Registry conflict              rawArgs[0]=int stepId,              [1]=String reason
```

---

## JFR Events (JFR-First Mandate)

Every critical lifecycle transition MUST emit a typed JFR event. No `Logger.info()` substitution.

### Required Events

| Event Class             | When Emitted                                    | Key Fields                                            |
|:------------------------|:------------------------------------------------|:------------------------------------------------------|
| `KernelBootstrapEvent`  | Start and end of each subsystem init            | `subsystemName`, `durationNanos`, `phase`             |
| `MemoryAllocationEvent` | Sampling path (1% of allocations)               | `sizeBytes`, `hint`, `tierName`, `latencyNanos`       |
| `MemoryExhaustionEvent` | On every `MemoryExhaustedException` throw       | `requestedBytes`, `availableBytes`, `allocatorName`   |
| `ArenaLeakEvent`        | `LeakTracker` detection (PARANOID/SAMPLED mode) | `segmentAddress`, `sizeBytes`, `allocationStackTrace` |
| `TransportBindEvent`    | On `Transport#bind()`                           | `transportName`, `port`, `protocol`                   |
| `CarrierPinnedEvent`    | Virtual thread pins carrier > threshold         | `blockTimeMs`, `carrierThreadName`, `stackTrace`      |

### JFR Event Pattern (Zero-Allocation)

```java
// JFR event: allocated per emission — JFR framework copies fields on commit().
// Exceptions are never on the hot-path; the single allocation here is acceptable.
// Do NOT share event instances across threads (JFR events are not thread-safe).
@jdk.jfr.Label("Memory Exhaustion")
@jdk.jfr.Category({"Exeris", "Memory"})
@jdk.jfr.StackTrace(false)  // suppress: stack trace = O(n) allocation
public final class MemoryExhaustionEvent extends jdk.jfr.Event {
    @jdk.jfr.Label("Requested Bytes")
    long requestedBytes;
    @jdk.jfr.Label("Available Bytes")
    long availableBytes;
    @jdk.jfr.Label("Allocator Name")
    String allocatorName;
}

static void emitExhaustion(long requested, long available, String name) {
    var event = new MemoryExhaustionEvent();
    event.requestedBytes = requested;
    event.availableBytes = available;
    event.allocatorName  = name;
    event.commit();
}
```

**Rule:** JFR `Event` subclasses must set `@StackTrace(false)` on all hot-path events.
Stack trace capture is O(depth) allocation — it is reserved for `LeakTracker` and `CarrierPinnedEvent` only.

---

## SPI Architecture

```
TelemetryRouter (SPI interface)
  │
  ├─ isEnabled()          → fast-path gate: if false, all emitXxx() are no-ops
  ├─ emitEvent(Event)     → async dispatch to registered sinks
  ├─ emitMetric(Metric)   → gauge/counter dispatch
  └─ emitSpan(Span)       → distributed trace span dispatch

TelemetrySink (SPI interface) — implemented by:
  ├─ [Community] JfrTelemetrySink          → writes to JFR event stream
  ├─ [Community] Slf4jTelemetrySink        → fallback structured logging
  └─ [Enterprise] DeterministicBinarySink  → writes to off-heap ring buffer (zero GC)
```

### ScopedValue Propagation

`TelemetryRouter` is propagated via `ScopedValue` (see `KernelProviders`).
**No static router singleton** — legacy `TelemetryRouter.isEnabled()` static method is banned.

```java
// ✅ CORRECT:
KernelProviders.TELEMETRY_PROVIDER.get().emitEvent(event);

// ❌ BANNED (static singleton — violates The Wall):
TelemetryRouter.emitMetric(metric);
```

---

## Community vs Enterprise Sinks

### Community (Free Tier)

- `JfrTelemetrySink` — writes typed JFR events. Zero external dependencies. Works with `jcmd` and JDK Mission Control.
- `Slf4jTelemetrySink` — fallback for environments without JFR. Emits structured JSON lines via SLF4J MDC.

### Enterprise (Secret Sauce — lives in `exeris-kernel-enterprise`)

- `DeterministicBinarySink` — writes deterministic binary frames to an **off-heap ring buffer**
  (`MemorySegment`) backed by a memory-mapped file. Buffer rotation is O(1). A background
  `StructuredTaskScope` flushes to disk without blocking the emission path. This is the Glass-Box
  principle at the hardware boundary: every kernel state transition is permanently recorded as a
  fixed-width, nanosecond-stamped binary frame — no string formatting, no GC interference, no data loss.
- **Schema:** fixed-width binary frames (see Binary Struct Mapping above). Compatible with
  `perf`/`ftrace`-style offline analysis tools.
- **SPI isolation:** `DeterministicBinarySink` imports only `exeris-kernel-spi` types.
  It never imports `MemoryManager`, `GlobalMemoryArbiter`, or any `kernel-legacy` class.

---

## Banned Patterns

| Pattern                                                      | Reason                                              | Replacement                                   |
|:-------------------------------------------------------------|:----------------------------------------------------|:----------------------------------------------|
| `String.formatted(...)` inside exception constructor         | Allocates StringBuilder                             | `rawArgs[]` primitives                        |
| `Map.of("key", value)` for metric tags on hot-path           | Creates anonymous Map class                         | Pre-allocated tag arrays or JFR fields        |
| `Logger.info("allocated {} bytes", size)` in allocation loop | SLF4J formats lazily but still allocates on enabled | JFR event with numeric fields                 |
| Static `TelemetryRouter.isEnabled()`                         | Breaks provider isolation (The Wall)                | `KernelProviders.TELEMETRY_PROVIDER.get().isEnabled()` |
| `Thread.currentThread().getStackTrace()` in hot-path events  | O(depth) object churn                               | Limit to `PARANOID` mode only                 |

---

## Testing Strategy

### Unit Tests

- Verify `rawArgs` index layout matches `KernelErrorCodes` Javadoc for every `ExerisKernelException` subclass.
- Verify `isEnabled() == false` results in zero allocations (measure via `Instrumentation.getObjectSize`).

### Integration Tests (TCK)

- `JfrTelemetrySink` correctly writes and the events are readable via `RecordingStream`.
- `DeterministicBinarySink` (Enterprise): ring-buffer does not overflow under 100k events/s; flush latency < 1 ms P99.

### Load Tests

- Emission of 1M `MemoryAllocationEvent` samples (1% sampling rate) adds < 50 µs/req overhead.
- Zero heap allocation delta (verified via `-Xverify:all` + JFR GC allocation profiler baseline).

