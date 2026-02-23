# Kernel Subsystem: Telemetry (L1 Observability)

**Physical Layout:**

- SPI (planned, not yet implemented in this repo): `eu.exeris.kernel.spi.telemetry.*` (`TelemetryRouter`, `TelemetrySink`, `TelemetryEvent`)
- Core: `eu.exeris.kernel.core.telemetry.*` (`JfrTelemetrySink`, `BinaryBlackBox`, `BlackBoxSerializer`)
- Enterprise: Binary crash-log sink, structured JFR streaming over off-heap ring buffer

**Layer:** L1 (Observability)  
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Telemetry subsystem** is the **Zero-Allocation Observability Pipeline** for the Exeris Kernel.
It is designed around a single invariant: **no `String` concatenation and no heap allocation may occur
on the event-emission hot-path**.

The core mechanism is the **Black-Box Pattern**: every `ExerisKernelException` subclass carries
a `rawArgs: Object[]` payload that encodes domain context as **raw primitives** (`long`, `int`, `Enum`).
These are serialised directly into a binary crash-log struct — bypassing `toString()`, `StringBuilder`,
and `Formatter` entirely.

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

## The Black-Box Pattern

### Problem

Legacy code performed formatting at throw-site:

```java
// ❌ BANNED — allocates StringBuilder + String on every exception construction:
throw new RuntimeException(
    "Memory exhausted: requested %d bytes, available %d bytes"
            .formatted(requestedBytes, availableBytes));
```

This causes GC pressure precisely when the system is under memory stress — the worst possible moment.

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

### Binary Struct Mapping (Enterprise Black-Box)

The Enterprise `BlackBoxSerializer` maps `rawArgs` to a fixed-width binary struct written
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
**and** register its error code in `KernelErrorCodes`.

```
EX-MEM-1001  Off-heap exhausted          rawArgs[0]=long requestedBytes, [1]=long availableBytes
EX-MEM-1002  Arena leak detected         rawArgs[0]=long segmentAddress, [1]=long segmentByteSize
EX-MEM-1003  AllocationHint conflict     (no rawArgs)
EX-BOOT-0002 Bootstrap failure           rawArgs[0]=String subsystemName, [1]=SubsystemException.Phase phase, [2]=String detail
EX-BOOT-0003 Bootstrap deadline exceeded rawArgs[0]=String subsystemName, [1]=long deadlineMs
EX-BOOT-0004 Memory provider bootstrap   rawArgs[0]=String providerName, [1]=long requestedBytes
EX-NET-4001  Transport bind/handshake    rawArgs[0]=String transportName, [1]=int port
EX-NET-4002  Transport send failure      rawArgs[0]=String transportName, [1]=long bytesSent
EX-NET-4003  Transport receive timeout   rawArgs[0]=String transportName, [1]=long timeoutMs
EX-SEC-2001  PrincipalContext missing     (no rawArgs)
EX-SEC-2002  Token validation failure    rawArgs[0]=String tokenType
EX-RUN-3002  Carrier pinned              rawArgs[0]=long blockTimeMs,    [1]=String carrierName
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
  ├─ [Community] JfrTelemetrySink       → writes to JFR event stream
  ├─ [Community] Slf4jTelemetrySink     → fallback structured logging
  └─ [Enterprise] BinaryBlackBoxSink    → writes to off-heap ring buffer (zero GC)
```

### ScopedValue Propagation

`TelemetryRouter` is propagated via `ScopedValue` (see `KernelProviders`).
**No static router singleton** — legacy `TelemetryRouter.isEnabled()` static method is banned.

```java
// ✅ CORRECT:
KernelProviders.TELEMETRY.get().emitEvent(event);

// ❌ BANNED (static singleton — violates The Wall):
TelemetryRouter.emitMetric(metric);
```

---

## Community vs Enterprise Sinks

### Community (Free Tier)

- `JfrTelemetrySink` — writes typed JFR events. Zero external dependencies. Works with `jcmd` and JDK Mission Control.
- `Slf4jTelemetrySink` — fallback for environments without JFR. Emits structured JSON lines via SLF4J MDC.

### Enterprise (Secret Sauce — lives in `exeris-kernel-enterprise`)

- `BinaryBlackBoxSink` — writes crash-log structs to an **off-heap ring buffer** (`MemorySegment`)
  backed by a memory-mapped file. Buffer rotation is O(1). A background `StructuredTaskScope` flushes
  to disk without blocking the emission path.
- **Schema:** fixed-width binary frames (see Binary Struct Mapping above). Compatible with
  `perf`/`ftrace`-style offline analysis tools.
- **SPI isolation:** `BinaryBlackBoxSink` imports only `exeris-kernel-spi` types.
  It never imports `MemoryManager`, `GlobalMemoryArbiter`, or any `kernel-legacy` class.

---

## Banned Patterns

| Pattern                                                      | Reason                                              | Replacement                                   |
|:-------------------------------------------------------------|:----------------------------------------------------|:----------------------------------------------|
| `String.formatted(...)` inside exception constructor         | Allocates StringBuilder                             | `rawArgs[]` primitives                        |
| `Map.of("key", value)` for metric tags on hot-path           | Creates anonymous Map class                         | Pre-allocated tag arrays or JFR fields        |
| `Logger.info("allocated {} bytes", size)` in allocation loop | SLF4J formats lazily but still allocates on enabled | JFR event with numeric fields                 |
| Static `TelemetryRouter.isEnabled()`                         | Breaks provider isolation (The Wall)                | `KernelProviders.TELEMETRY.get().isEnabled()` |
| `Thread.currentThread().getStackTrace()` in hot-path events  | O(depth) object churn                               | Limit to `PARANOID` mode only                 |

---

## Testing Strategy

### Unit Tests

- Verify `rawArgs` index layout matches `KernelErrorCodes` Javadoc for every `ExerisKernelException` subclass.
- Verify `isEnabled() == false` results in zero allocations (measure via `Instrumentation.getObjectSize`).

### Integration Tests (TCK)

- `JfrTelemetrySink` correctly writes and the events are readable via `RecordingStream`.
- `BinaryBlackBoxSink` (Enterprise): ring-buffer does not overflow under 100k events/s; flush latency < 1 ms P99.

### Load Tests

- Emission of 1M `MemoryAllocationEvent` samples (1% sampling rate) adds < 50 µs/req overhead.
- Zero heap allocation delta (verified via `-Xverify:all` + JFR GC allocation profiler baseline).

