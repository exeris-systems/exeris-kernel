# Enterprise Performance Contract

> **Cross-reference:** Open-core [`docs/performance-contract.md`](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/performance-contract.md)

## Reference Hardware

All Enterprise performance benchmarks and SLOs are measured on:

| Property | Value |
|:---------|:------|
| Instance | AWS `c7g.4xlarge` (Graviton3) |
| vCPUs | 16 |
| Memory | 32 GiB |
| Network | Up to 12.5 Gbps |
| OS | Amazon Linux 2023 (kernel 6.1+) |
| JDK | OpenJDK 26 GA + preview features |

All JMH benchmarks must produce reproducible results on this configuration. CI benchmarks
run in JFR recording mode to capture allocation profiles.

---

## Enterprise SLO Table

| Metric | Community Tier | Enterprise Tier | Enforcement |
|:-------|:--------------|:----------------|:------------|
| Throughput | > 1,500 RPS/vCPU | **> 8,500 RPS/vCPU** | JMH histogram |
| P99 request latency | ≤ 200 µs | **≤ 50 µs** | JMH `@BenchmarkMode(Percentiles)` |
| Allocation rate | < 512 B/req | **0 B/req (strict)** | `CryptoZeroAllocTck` + JFR |
| Bootstrap cold start | ≤ 1,500 ms P99 | **≤ 800 ms P99** | Nanosecond timer in TCK |
| Saga state transition | ~2–5 ms | **≤ 1 µs P99** | `SagaStateTransitionBenchmark` |

Breaching any of these is a **blocking defect** — not a performance regression.

---

## TLS Hot-Path: 0 Bytes Heap Allocation

**Hard guarantee.** The TLS `wrap()`/`unwrap()` hot path produces **zero `eu.exeris.*`
heap allocations.** This is enforced by `CryptoZeroAllocTck`:

```java
// TCK enforcement — JFR AllocationEvent listener
FlightRecorder.getFlightRecorder().startRecording(allocationConfig);
engine.wrap(plaintextBuf, ciphertextBuf);   // measured call
Recording rec = FlightRecorder.getFlightRecorder().stopRecording();

long euExerisAllocs = rec.getEvents().stream()
    .filter(e -> e.getClass().getName().startsWith("eu.exeris."))
    .mapToLong(e -> (long) e.getValue("allocationSize"))
    .sum();

assertThat(euExerisAllocs)
    .as("TLS wrap() must produce 0 eu.exeris.* heap allocations")
    .isZero();
```

The guarantee covers:
- `EnterpriseQuicTlsEngine.wrap()` and `.unwrap()`
- `QuicBioMultiplexer.injectDatagram()`
- All `QuicSslHandles.invokeExact()` callsites

---

## io_uring Steady-State: 0 Syscalls

In steady-state (post-bootstrap, active connections, inbound traffic), the
`IoUringCarrierLoop` makes **zero additional syscalls per received datagram.**

This is achieved by:
- **Multishot `recvmsg`**: One SQE generates unlimited CQEs until cancelled. No syscall per packet.
- **`PBUF_RING`**: Kernel delivers packets directly into pre-registered slabs. No `mmap` per receive.
- **Batched `sendmsg`**: All outbound datagrams for one event-loop tick are submitted in a single `io_uring_enter`.

Enforcement: JFR `SyscallEvent` listener in `IoUringZeroSyscallTck`.

---

## Graph Traversal: < 1x Churn-to-Data Ratio

For every byte of application-domain graph data returned, fewer than one byte of
`eu.exeris.*` heap allocation is permitted.

```
churnBytes / dataBytes < 1.0
```

Enforcement: `GraphChurnRatioTck` measures JFR `AllocationEvent` during a fixed-topology
traversal and asserts the ratio.

---

## Bootstrap Cold Start: ≤ 800 ms P99

Full `KernelBootstrap.start()` → first request handled in ≤ 800 ms on reference hardware.
This includes:
- `GlobalMemoryArbiter` mmap + partition claim + seal
- `io_uring_setup` × N carrier threads
- `ProvidedBufferRing` slab registration
- OpenSSL context initialization
- Native PG connection pool warm-up

Enforcement: nanosecond timer in `EnterpriseBootstrapTimingTck`.

---

## Saga State Transition: ≤ 1 µs P99

In-memory saga state transitions (VarHandle CAS on off-heap slot) must complete in ≤ 1 µs
P99. This excludes async write-behind to the Event Store.

Enforcement: `SagaStateTransitionBenchmark` (JMH nanosecond histogram).

---

## Slab Allocation: 0 GC Objects per `allocate()`

`PartitionedSlabPool.allocate()` must produce **zero GC-visible objects** (no `new`
expressions on the hot path, no autoboxing, no lambda allocation).

Enforcement: `ExecutionGraphZeroAllocTck` — JFR `AllocationEvent` listener.

---

## PAQS Shed Decision: ≤ 5 µs

The PAQS (Priority Admission & Queue Scheduler) load-shedding decision — from request
arrival to `SHED` decision — must complete in ≤ 5 µs.

Enforcement: nanosecond timer inline in PAQS carrier loop + JFR `PaqsShedEvent`.

---

## TCK Enforcement Matrix

| TCK Class | SLO Enforced | Method |
|:----------|:-------------|:-------|
| `CryptoZeroAllocTck` | 0 B/req on TLS wrap/unwrap | JFR `AllocationEvent` |
| `IoUringZeroSyscallTck` | 0 syscalls steady-state | JFR `SyscallEvent` |
| `GraphChurnRatioTck` | < 1x churn-to-data | JFR `AllocationEvent` |
| `ExecutionGraphZeroAllocTck` | 0 GC objects per slab alloc | JFR `AllocationEvent` |
| `EnterpriseBootstrapTimingTck` | ≤ 800 ms cold start | Nanosecond timer |
| `SagaStateTransitionBenchmark` | ≤ 1 µs P99 saga transition | JMH nanosecond histogram |
| `EnterpriseLatencyTck` | ≤ 50 µs P99 request latency | JMH histogram + `AssertionError` |
| `EnterpriseThroughputTck` | > 8,500 RPS/vCPU | JMH `@BenchmarkMode(Throughput)` |

All TCK classes extend the open-core `Abstract*Tck` base classes. Running
`mvn clean install` executes all TCK tests as part of the standard lifecycle.

---

## JFR Recording Profile

All TCK tests use a common JFR recording configuration:

```xml
<!-- enterprise-tck-recording.jfc -->
<event name="jdk.ObjectAllocationInNewTLAB">
  <setting name="enabled">true</setting>
  <setting name="stackTrace">false</setting>
</event>
<event name="jdk.ObjectAllocationOutsideTLAB">
  <setting name="enabled">true</setting>
  <setting name="stackTrace">false</setting>
</event>
```

`@StackTrace(false)` is mandatory on all Enterprise JFR events — stack capture at native
boundary doubles recording overhead.

---

## Relationship to Community Performance Contract

Enterprise does not relax any Community guarantee. All Community contracts (e.g., < 5 µs
PAQS shed decision, zero-allocation on SPI hot paths) remain in force. Enterprise tightens
the overall system SLO by eliminating the JDBC Tax (Community: ~8 ms per query) and the
Syscall Tax (Community: 1 syscall per network operation).

---

## Cross-References

- [Architecture Overview](../architecture.md)
- [Memory Subsystem](./subsystems/memory.md) — slab zero-allocation guarantee
- [Crypto Subsystem](./subsystems/crypto.md) — TLS hot-path allocation contract
- [Transport Subsystem](./subsystems/transport.md) — io_uring zero-syscall contract
- [Graph Subsystem](./subsystems/graph.md) — churn-to-data ratio
- [Flow Subsystem](./subsystems/flow.md) — saga transition latency
- Open-core: [`docs/performance-contract.md`](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/performance-contract.md)