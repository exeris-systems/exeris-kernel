---
title: "ADR-007: Next-Gen Runtime Architecture (Exeris Kernel)"
type: adr
slug: adr/ADR-007
visibility: public
owning-repo: exeris-kernel
status: active
---

# ADR-007: Next-Gen Runtime Architecture (Exeris Kernel)

| Attribute      | Value                                                  |
|:---------------|:-------------------------------------------------------|
| **Status**     | **ACCEPTED**                                           |
| **Deciders**   | Arkadiusz Przychocki                                   |
| **Date**       | 2025-12-11 (Updated: 2026-02-22)                       |
| **Compliance** | [Strategic Pillar: No-Waste Compute](../whitepaper.md) |
| **Amended**    | 2026-10-07 — §5 Kernel Lifecycle and Process Termination (**PROPOSED**), see [Amendments](#amendments) |

## Context and Problem Statement

To achieve **"Hyper-Density"** (100k+ concurrent connections per node), legacy Java frameworks (Netty/WebFlux) are
insufficient due to **"Callback Hell"**, JNI overhead, and massive heap allocations. We need a runtime that leverages
the full power of **Java 26** to achieve zero-copy, zero-allocation, and imperative code simplicity.

## 🏁 The Decision

We build the **Exeris Kernel** as a tiered, vertical architecture (**"The Wall"**), rejecting traditional monolithic
designs.

### 1. Tiered Vertical Layout ("The Wall")

Strict separation into layers by trust and execution role:

- **SPI:** Pure contracts and value records. Zero implementation details.
- **Core:** Protocol-agnostic orchestration (The Brain).
- **Drivers (Community/Enterprise):** Protocol-specific execution (The Muscle).

### 2. Execution Model

We reject event loops in favor of the **Virtual Thread-per-request** model (Project Loom).

- **Virtual Threads (JEP 444/491):** 1:1 Request-to-Thread mapping. ~100 bytes of memory vs. ~1 MB for OS threads.
- **Scoped Values (JEP 506):** Immutable, Virtual Thread-safe context propagation. `ThreadLocal` is **BANNED**.
- **Structured Concurrency (JEP 525):** All parallel operations are strictly bound within a
  `StructuredTaskScope`. The sole exception is PAQS ingress: `StructuredTaskScope.fork()` enforces
  `WrongThreadException` for any caller that did not open the scope, making a shared long-lived STS
  incompatible with the multi-carrier ingress model (NIO selectors + io_uring rings calling `schedule()`
  concurrently). Per-stream VTs spawned by PAQS act as Request Tree roots; all operations within them
  MUST run inside a structured scope.

  > **Amended 2026-08-08 by ADR-066 — mechanism, not principle.** The requirement that every parallel
  > operation be bound within a scope that owns its lifetime stands unchanged; what changed is which
  > scope implements it, and it now differs by distribution line. The distributed artifact uses
  > `eu.exeris.kernel.core.concurrent.StructuredScope` (virtual threads + explicit `ScopedValue`
  > carrier, both GA) so that it imposes no `--enable-preview` on its consumers; the `preview` branch
  > keeps `StructuredTaskScope`. Two call sites reached the principle a third way: where a task must
  > observe `ScopedValue` bindings the kernel does not define — an application's own — no fork can
  > deliver them, and the work runs on the calling thread instead. The owner-thread constraint cited
  > above for PAQS is unchanged: `StructuredScope` enforces the same confinement, by design.

### 3. Protocol-Agnostic Transport (L2)

We reject the concept of a "QUIC-only" kernel.

- **Community Tier:** Standard **TCP / HTTP/2** based on non-blocking NIO.2.
- **Enterprise Tier:** **QUIC / HTTP/3** using **Panama FFM** and **io_uring** for direct kernel-bypass I/O
  (RFC 9000).
- **PAQS:** Priority-Aware Queue Scheduler integrated at the transport edge.

### 4. Memory Management (Loan Pattern)

We abandon buffer ownership by business logic. Buffers are exclusively **"loaned"**.

- **Zero-Allocation:** Buffers are never "owned" by business logic; they are "loaned" via `LoanedBuffer` (SPI).
- **FFM API:** All high-performance I/O takes place in **Off-Heap** segments (`MemorySegment`).
- **Ref-Counting:** `VarHandle`-based atomic reference counting eliminates GC pressure.
- **MemoryAllocator:** All allocations MUST go through `MemoryAllocator`. Direct use of `Arena.ofConfined()` or
  `Arena.ofShared()` in business logic is **BANNED**.

### 5. Kernel Lifecycle and Process Termination

> **PROPOSED 2026-10-07, planned for 0.13** — see [Amendments](#amendments). Nothing in this section is
> implemented yet; until it is, `docs/subsystems/bootstrap.md` describes the shipped behaviour.

#### 5.1 Context

`KernelBootstrap.boot(Runnable)` runs `kernelMain` on the calling thread and calls
`SubsystemOrchestrator.shutdown()` in a `finally` once `kernelMain` returns or throws. That is the only path
to an ordered stop. Measured on `development/0.13.0`:

- No `Runtime.addShutdownHook`, `sun.misc.Signal`, `System.exit` or `Runtime.halt` call exists in any
  kernel module's main sources (the only `System.exit` calls are in the `tools/jfr-reporter` command-line
  entry point).
- `KernelBootstrap` has no public `shutdown()` or `close()`. Its public instance surface is `boot`,
  `inspect` and `healthMonitor`.
- `SubsystemOrchestrator.shutdown()` guards re-entry with `terminated.get()` followed by
  `terminated.set(true)` — two steps, not a compare-and-set — so two concurrent callers can both run the
  stop sequence.
- The ordered stop already exists: `SubsystemOrchestrator.shutdown()` marks the kernel `SHUTTING_DOWN`
  (readiness turns `503`), then calls `stop()` in reverse topological order. The transport stops first:
  `NativeTcpCarrier.stop()` closes ingress, drains streams in service through `PaqsScheduler.close()`
  bounded by `PaqsScheduler.DRAIN_DEADLINE_NANOS` (60 s), then tears down. Memory stops last. Both
  `NativeTcpCarrier.stop()` (a CAS on `draining`) and `PaqsScheduler.close()` (a sealed coordinator) are
  already idempotent.

A container stop sends `SIGTERM`. With no hook registered, the JVM exits without running any of this: in
flight requests are cut, subsystems are not stopped, the allocator is not released, and readiness stays
`200` until the process is gone (#542). `AbstractGracefulShutdownTck` states the ordering contract and has
no binding (#461).

An embedding host has no entry point either. The Spring host runtime stops the kernel from its
`SmartLifecycle.stop()` by releasing the latch its `kernelMain` waits on; it also looks up `shutdown()` and
`close()` on `KernelBootstrap` reflectively, finds neither, and falls through. The latch is the path that
works today.

#### 5.2 Options considered

- **A. The host always owns signals (the 0.12 position).** The kernel installs nothing; every
  deployment writes its own hook that makes `kernelMain` return. Cost: the default deployment — a
  container running `java -jar` — is a crash stop on every `SIGTERM`, and the `AbstractGracefulShutdownTck`
  contract holds only for hosts that remember to write the hook. It also leaves the embedding host without
  a direct stop call.
- **B. The kernel always installs a hook.** Cost: an embedding host with its own hook (Spring Boot
  registers one by default, which closes the context and so calls `SmartLifecycle.stop()`) gets two
  stop sequences started concurrently on `SIGTERM`. The JVM starts shutdown hooks in no specified order and
  runs them concurrently, so the kernel would stop on its own schedule, not at the host's lifecycle phase.
  A process that boots many kernels in sequence (the `exeris-kernel-community-testkit` fixtures do) accumulates hooks
  unless each is removed.
- **C. A public, idempotent stop entry point, plus one hook that an embedding host turns off
  *(recommended)*.** Standalone boot gets an orderly `SIGTERM` stop with no host code; an embedding host
  keeps sole ownership of signals and calls the entry point from its own lifecycle. Cost: one new public
  method and one boot option, and an obligation on every `Subsystem.stop()` stated in §5.3.4.

#### 5.3 Decision (proposed)

##### 5.3.1 A public stop entry point

`KernelBootstrap` gains a public `shutdown()`:

- **Idempotent and race-safe.** Any number of calls, from any thread, concurrently with `kernelMain`
  returning, run the stop sequence exactly once. `SubsystemOrchestrator.shutdown()` enters through a
  compare-and-set, replacing its two-step guard.
- **Returns after the stop has completed.** A caller that loses the race waits until the winner has
  finished, whichever thread that is. A hook that returned early would let the JVM halt mid-drain.
- **Never throws.** A `stop()` that throws is logged and skipped, as `SubsystemOrchestrator.shutdown()`
  does today.
- **Outside a running boot.** Before `boot()`, after `boot()` has returned, and during `inspect()` (which
  initializes nothing), it is a no-op.
- **During boot.** A call that arrives before `kernelMain` has started records the request; `boot()`
  then does not run `kernelMain`, and the stop runs in its `finally` once initialization has unwound.
- **It does not make `kernelMain` return.** `kernelMain` is application code; the kernel does not
  interrupt it. A standalone process exits once the hook has finished; an embedding host releases its own
  wait, as the Spring host runtime already does.

##### 5.3.2 One JVM shutdown hook in standalone boot

- `boot()` registers exactly one shutdown hook per boot, after the orchestrator is built and before any
  subsystem is initialized, so a `SIGTERM` that arrives during start-up also stops what has started.
- The hook's whole body is `shutdown()`. It runs on a named **platform** thread.
- `boot()` removes the hook in its `finally` after the stop has completed. `Runtime.removeShutdownHook`
  throws `IllegalStateException` once JVM shutdown is in progress; that case is expected (the hook is the
  caller) and is ignored.
- `inspect()` registers no hook.
- The ordered stop the hook runs is the existing one (§5.1): readiness `503`, transport ingress close, drain
  bounded by `DRAIN_DEADLINE_NANOS`, reverse topological `stop()`, memory and allocator release last. This
  amendment adds a trigger, not a new order.
- No stop path calls `System.exit` or `Runtime.exit`: inside a hook that call blocks forever, and the
  kernel hook waits on it.

##### 5.3.3 An embedding host turns the hook off

An embedding host disables the hook when it builds the bootstrap, and calls `shutdown()` from its own
lifecycle — for the Spring host runtime, from `SmartLifecycle.stop()`, alongside or instead of the latch
release. Exactly one party owns signals, so `SIGTERM` starts one stop sequence. Because `shutdown()` is
idempotent a second call is harmless, but that does not make two owners ordered, which is why the hook
is turned off rather than raced.

- **Default — Ruling required.** Recommended: **opt-out** (installed unless disabled). A default that
  stays crash-stop for every deployment that has not read this section repeats Option A. Consequence: an
  embedding host must disable the hook in the same release that adopts the kernel version carrying it,
  or it runs two owners (Option B's cost) until it does.
- **Switch — Ruling required.** Recommended: a `KernelBootstrap.Builder` method, because an embedding host
  builds the bootstrap in code (the Spring host runtime calls `KernelBootstrap.builder()`), and a boot
  option cannot be overridden by a configuration file the host does not control. Whether a configuration
  key also exists — so a standalone operator who owns signals can turn the hook off without code — and
  its name are open. Existing kernel keys are `<subsystem>.<camelCase>` (for example
  `http.h2cUpgradeEnabled`); no key under a `kernel.` or `bootstrap.` prefix is read through
  `ConfigProvider`, so there is no namespace to follow. If a key exists,
  the builder setting wins over it.

##### 5.3.4 What must hold on the stopping thread

The stop runs on whichever thread wins the race: the boot thread in `boot()`'s `finally`, the hook
thread, or the host's lifecycle thread. Two of those carry none of the kernel's `ScopedValue` bindings.

- **`Subsystem.stop()` does not read kernel `ScopedValue` bindings.** It acts on references captured at
  `initialize()`/`start()`. Measured, shallow: no `stop()` or `doStop()` body in the Community bootstrap
  package reads `KernelProviders` or a `ScopedValue` directly; transitive reads are not measured, which is
  what the unbound-thread test in §5.4 is for.
- **No virtual thread is forked from the stop path to do work that needs kernel bindings.** A thread
  forked from the hook inherits the hook thread's bindings, which are none.
- **JFR events on the stop path are single-phase.** The hook thread is a platform thread, so the
  virtual-thread `begin()`/blocking/`commit()` hazard does not arise on it, but the same `stop()` also runs
  on the boot thread, which an application may run as a virtual thread. A `stop()` emits after the
  operation it reports, as `CommunityTransportDrainEvent.emit` does.
- **Diagnostics after `SIGTERM` are best-effort.** The JVM runs its own shutdown hooks concurrently with
  the kernel's: a recording dumped on exit, and `java.util.logging` handlers that the log manager resets at
  shutdown, may miss what the kernel emits during the stop. The kernel does not order itself against them.

##### 5.3.5 Non-Goals

- **No overall stop deadline.** `shutdown()` remains bounded only where the transport drains (60 s). The
  platform's grace period stays the authority beyond that, as the `DRAIN_DEADLINE_NANOS` rationale states.
- **No signal handling beyond the JVM's.** The kernel installs no `sun.misc.Signal` handler; `SIGTERM`,
  `SIGINT` and `SIGHUP` reach it only through the JVM's shutdown sequence.
- **No change to the drain or to subsystem order.**

##### 5.3.6 Risks and Assumptions

- **Assumes:** no `Subsystem.stop()` depends, directly or transitively, on a kernel `ScopedValue`
  binding (§5.3.4 measured direct reads only). **Reversed by:** the unbound-thread test in §5.4
  failing for a `stop()` that cannot be rewritten to use references captured at start — the hook then
  cannot run the ordered stop on its own thread, and the stop has to be marshalled onto a bound thread
  instead.
- **Assumes:** the JVM runs shutdown hooks to completion within the platform's grace period, so a
  `SIGTERM` leaves time for the bounded drain. **Reversed by:** a supported deployment whose grace
  period is shorter than the drain deadline as a rule — the drain bound then belongs in configuration,
  which §5.3.5 leaves out.
- **Assumes (opt-out default):** embedding hosts adopt the kernel version carrying the hook together
  with disabling it. **Reversed by:** an embedding host that cannot disable the hook in the release that
  adopts it — the default then becomes opt-in, accepting crash-stop for deployments that do not opt in.
- **Risk:** the JVM's own hooks (dump-on-exit recording, `java.util.logging` reset) run concurrently with
  the kernel's, so diagnostics emitted during the stop can be lost. The forked-process test in §5.4 is
  the first to see it, which is why it records stop order through its own channel.

#### 5.4 Verification obligations (planned for 0.13)

- **Real signal, separate process.** A test forks a JVM that boots the Community kernel with an HTTP
  handler held on a latch, starts a request, and sends that process a real `SIGTERM`. It asserts: the
  in-flight request receives a complete response; a new connection after ingress closes is refused;
  subsystems stop in the canonical order with memory last; the process exits with status 143. The child
  records stop order through its own channel (a test subsystem writing to a file or standard error), not
  through a dump-on-exit recording or `java.util.logging` (§5.3.4).
- **Negative control.** The same test with the hook disabled observes the in-flight request cut, so the
  positive case is shown to depend on the hook.
- **Race.** `shutdown()` called concurrently with `kernelMain` returning, over repeated fresh boots, runs
  each `stop()` once, and every caller returns only after the last `stop()` has returned.
- **Unbound thread.** `shutdown()` called from a platform thread with no kernel bindings completes the
  ordered stop.
- **No accumulation.** Booting and returning repeatedly in one JVM leaves no kernel hook registered after
  each `boot()` returns.
- **`AbstractGracefulShutdownTck` binding (#461)** whose `orchestratorShutdown` callback is
  `KernelBootstrap.shutdown()`, so the suite judges the shipped stop path and not test wiring.
- **Embedding host.** The Spring host runtime disables the hook and calls `shutdown()` from
  `SmartLifecycle.stop()`; its integration test asserts that one `SIGTERM` runs one stop sequence.
- `docs/subsystems/bootstrap.md` §"Shutdown Is Driven by the Caller", its shutdown sequence diagram and
  its rolling-deployment notes change in the same pull request as the implementation.

## ⚠️ Amendment: JEP 491 & Synchronization

**Update (Java 24+):** The `synchronized` keyword is no longer a global taboo.

- **PERMITTED:** For critical sections within off-heap memory pool internals (e.g., `SlabPool` slab
  reclamation, `SegmentPool` free-list management) where lock-free CAS sequences are susceptible to
  ABA problems — specifically, when a pointer can be reclaimed and reallocated between a `compareAndSet`
  load and its subsequent store, invalidating the assumption of pointer uniqueness. In these narrow cases,
  a brief `synchronized` block provides correctness guarantees that a single-word CAS cannot.
- **STRICTLY BANNED:** Around native FFM `downcall` invocations or any blocking I/O, as this can still induce
  **Thread Pinning**.

## Consequences

### ✅ Positive Outcomes

* **[+] Zero-Copy Hot Path:** Data moves from the NIC to the DB driver without hitting the JVM Heap.
* **[+] Operational Clarity:** Standard stack traces and deterministic error codes (`EX-`) replace reactive
  "debug-hell".
* **[+] Hardware Efficiency:** Direct mapping to NUMA nodes and CPU cache lines in the Enterprise tier.

### ⚠️ Trade-offs

* **[-] Tier Fragmentation:** Maintaining two transport stacks (TCP vs. QUIC) increases the testing surface.

* **[-] High Barrier to Entry:** Contributing to the Kernel requires mastery of a specific set of non-standard
  engineering disciplines. Concretely, a contributor must understand:

  1. **Off-Heap Memory Release Rules:** Every `MemorySegment` acquired through `MemoryAllocator` is bound to
     an `Arena`. The contributor must understand the Arena lifecycle (confined vs. shared vs. auto), know when
     to call `LoanedBuffer.release()`, and understand that forgetting to do so is a **silent memory leak** — not
     a Java `OutOfMemoryError`. Over-releasing (double-free) results in `IllegalStateException` on the release
     path, but may cause a `SIGSEGV` if the underlying native pointer is reused by the allocator.

  2. **`LoanedBuffer` Contract and `Arena.ofConfined()` Ban:** Business logic must **never** call
     `Arena.ofConfined()` or `Arena.ofShared()` directly. All allocations flow through `MemoryAllocator` to
     guarantee tier-specific slab pooling and `LoanedBuffer` ref-count lifecycle. Any direct Arena usage
     bypasses the pool and breaks the Zero-Allocation Covenant.

  3. **Glass-Box Debugging via JFR:** Native FFM `downcall` frames are opaque to the standard JDWP debugger.
     Debugging a failed `SSL_do_handshake` or a corrupted `io_uring` SQE requires reading JFR event streams
     (custom `@StackTrace(false)` events emitted around every FFM callsite) and correlating them with
     `perf`/`bpftrace` traces on the kernel side. The standard "set a breakpoint and inspect" workflow
     does not apply to the off-heap execution path.

## Amendments

- **2026-10-07 — §5 Kernel Lifecycle and Process Termination (PROPOSED, planned for 0.13).** New
  section; no earlier text is changed. Adds a public idempotent `KernelBootstrap.shutdown()`, one JVM
  shutdown hook registered by standalone `boot()` that runs the existing ordered stop on `SIGTERM`, and a
  switch an embedding host uses to turn the hook off and call `shutdown()` from its own lifecycle. Two
  points await a ruling: whether the hook is opt-out or opt-in, and whether a configuration key exists
  beside the builder switch. Driven by #542; the TCK binding is #461.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.
