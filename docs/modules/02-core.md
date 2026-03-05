# Physical Tier: Core (The Brain)

**Module:** `exeris-kernel-core`
**Dependencies:** `exeris-kernel-spi` ONLY.

## 🧠 Architectural Rules (L0 Enforcement)

1. **Driver Agnosticism:** Core must NEVER know if it's running on Community or Enterprise drivers. It interacts
   exclusively via `ServiceLoader` and SPI contracts.
2. **Orchestration Only:** Core makes decisions (Watermarks, Load Shedding, Backpressure), but does not execute the
   physical I/O.
3. **Structured Concurrency (JDK 25+ Joiner API):** By default, all concurrent *subtasks* in Core must be created via
   `StructuredTaskScope.open(Joiner)` with an explicit `Joiner`. `ThreadLocal` and raw `ExecutorService` are entirely
   BANNED. Custom `Joiner` implementations should be used for complex aggregation to ensure typed, zero-cast handover
   of subtask results (e.g., `LoanedBuffer`). `ScopedValue` context is propagated strictly into forked subtasks. The only
   sanctioned exceptions are: (a) request-root / entrypoint virtual threads that own their own structured scopes (e.g.,
   the root thread that opens a `StructuredTaskScope` for downstream work), (b) long-lived background maintenance loops
   whose lifetime equals the JVM or subsystem (e.g., `MemoryMaintenanceTask`), and (c) PAQS per-stream virtual threads
   where the stream lifecycle defines the concurrency scope (`PaqsScheduler`). These exceptions must be explicitly
   documented in-code and must not reintroduce ad-hoc executors or unstructured concurrency.
4. **Fail-Fast Bootstrap:** Must validate all injected SPI providers at T-minus 0 and halt the JVM if contracts are not
   met.