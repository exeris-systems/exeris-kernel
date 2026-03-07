# Contributing to Exeris Kernel

Welcome. This document is the **minimum viable onboarding guide** for contributors to
`exeris-kernel`. Read it before opening a PR — it will save you a review cycle.

The Exeris Kernel is not a standard Java application. It is a system-level runtime engineered
for zero-copy, zero-allocation hot paths. The patterns used here (Panama FFM, `LoanedBuffer`,
`ScopedValue`, off-heap memory) are deliberately non-standard. This document exists to lower
the barrier to contribution, not to enforce bureaucracy.

---

## Prerequisites

### Java Version

**Java 26 (EA or GA) is required.** The kernel actively uses APIs that are preview or finalising
in Java 26:

| Feature                            | JEP       | Status in JDK              | Used in Kernel                                    |
|:-----------------------------------|:----------|:---------------------------|:--------------------------------------------------|
| Virtual Threads                    | JEP 444   | Stable (JDK 21)            | Every request-handling path                       |
| Structured Concurrency (Joiner)    | JEP 525   | Preview → finalising       | `StructuredTaskScope.open(Joiner)` in Core/Bootstrap |
| Scoped Values                      | JEP 506   | Preview → finalising       | `KernelContext`, `StorageContext`, `PrincipalContext` |
| Foreign Function & Memory (FFM)    | JEP 454   | Stable (JDK 22)            | OpenSSL bindings, off-heap I/O, `io_uring`        |
| Flexible Constructor Bodies        | JEP 513   | **Closed / Delivered (JDK 25)** | Field pre-init before `super()` in value-ready types |
| Valhalla Value Classes (prep)      | JEP 401   | Early Access preview       | **Not yet used.** All data carriers (`record`, `final class`) are designed to be migration-ready: no `synchronized`, no `System.identityHashCode()`, no identity `==` on domain objects. C2 JIT Escape Analysis scalarises them on hot-paths today. |
| Lazy Constants                     | JEP 526   | **Closed / Delivered (JDK 26)** | `LazyConstant.of(...)` for singleton config caches and expensive one-time initialisations |

The project POM enables preview features globally. Do **not** disable `--enable-preview` flags
in any module — doing so will break compilation of `exeris-kernel-enterprise` and parts of Core.

**Recommended toolchain:**
```
sdk install java 26-open   # SDKMAN (Linux/macOS)
# or download from https://jdk.java.net/26/
```

Verify:
```powershell
java -version
# Expected: openjdk version "26" or higher
```

### Native Libraries (Linux required for full test suite)

The `exeris-kernel-community` and `exeris-kernel-core` TLS tests call into OpenSSL 3.x via
Panama FFM. On **Linux**, the tests expect `libssl.so.3` on `LD_LIBRARY_PATH`. On **macOS**,
`libssl.3.dylib` must be available (via Homebrew `openssl@3`). On **Windows**, only unit tests
that do not invoke FFM symbols will pass — TLS integration tests are gated by the
`os.name` system property and skip automatically on Windows.

```bash
# Linux (Debian/Ubuntu)
sudo apt-get install libssl-dev

# macOS
brew install openssl@3
export DYLD_LIBRARY_PATH="$(brew --prefix openssl@3)/lib:$DYLD_LIBRARY_PATH"
```

### Other Tools

| Tool       | Minimum Version | Purpose                                      |
|:-----------|:----------------|:---------------------------------------------|
| Maven      | 3.9+            | Build system                                 |
| Docker     | 24+             | Local environment (Postgres, Redis)          |
| Podman     | 4.x (alternative) | Drop-in Docker replacement                 |
| `jcmd`     | bundled with JDK | JFR snapshot inspection                    |
| JDK Mission Control (JMC) | 9.0+ | Visual JFR analysis (optional)        |

---

## Build & Test

### The Golden Command

```bash
mvn clean install
```

This is the **only** command that counts. `mvn clean compile` is not sufficient — it skips:
- PMD static analysis (Priority 1-3 rules including banned `ThreadLocal` and `ExecutorService` detection)
- TCK execution in `LeakDetectionMode.PARANOID`
- SPI isolation verification (ensures `exeris-kernel-spi` has no implementation-specific imports)
- JFR-based zero-allocation validation on the `wrap()`/`unwrap()` hot path

**Expected output (clean build):**
```
[INFO] exeris-kernel-spi .......................... SUCCESS
[INFO] exeris-kernel-core ......................... SUCCESS
[INFO] exeris-kernel-community .................... SUCCESS
[INFO] exeris-kernel-tck .......................... SUCCESS
[INFO] exeris-kernel-enterprise ................... SUCCESS (proprietary, may skip)
[INFO] BUILD SUCCESS
```

### Running a Single Module

```bash
mvn clean install -pl exeris-kernel-core -am
# -am = also build dependencies (spi)
```

### Skipping Native Tests on Windows

If you are on Windows and do not have OpenSSL available, FFM-based TLS tests will be auto-skipped.
To confirm which tests were skipped:

```powershell
mvn clean install -Dexeris.native.skip=true
```

### TCK Paranoid Leak Mode

The TCK runs with `LeakDetectionMode.PARANOID` by default. This mode verifies that every
`LoanedBuffer` acquired during a test is released before the test method exits. A failed
`LeakDetectedError` means a buffer was not returned to the pool — fix the lifecycle, not the test.

To run only the TCK:
```bash
mvn test -pl exeris-kernel-tck
```

---

## Local Environment

The full local environment requires only **Docker or Podman**. No cloud account, no managed service,
no port-forwarding magic. This is a direct consequence of ADR-001 (Cloud Agnostic Infrastructure).

### Start the Local Stack

```bash
docker compose up -d
```

The `docker-compose.yml` at the repository root provisions:
- **PostgreSQL 16** on `localhost:5432` (used by `JdbcCitadelRepository` in Community tier)
- **Redis 7** on `localhost:6379` (session/distributed cache, used by Security subsystem)

Default credentials match the test fixtures in `src/test/resources/application-test.properties`.
Do not change them without updating all test fixtures.

### Stopping the Stack

```bash
docker compose down
```

Data is ephemeral by default (no named volume mounts). Each `up` starts with a clean database.

---

## JFR Inspection

Exeris emits custom JFR events under the `Exeris` category. They are the primary observability
mechanism — not log files.

### Starting a JFR Recording

```bash
# Attach to a running JVM (replace <pid> with the actual process ID)
jcmd <pid> JFR.start name=exeris-debug settings=profile duration=60s filename=debug.jfr

# Or start the JVM with recording enabled from the beginning:
java -XX:StartFlightRecording=name=boot,settings=profile,filename=boot.jfr \
     --enable-preview \
     -jar exeris-kernel-core/target/exeris-kernel-core.jar
```

### Locating Exeris Events in JMC

1. Open `debug.jfr` in **JDK Mission Control (JMC)**.
2. Navigate to **Event Browser** → expand **Exeris Kernel**.
3. Key event categories:

| JFR Category                  | Event Class                                  | What it tells you                               |
|:------------------------------|:---------------------------------------------|:------------------------------------------------|
| `Exeris Kernel / Memory`      | `MemoryAllocationEvent`                      | Off-heap allocation sample (1% rate)            |
| `Exeris Kernel / Memory`      | `TelemetryJfrEvents.MemoryExhaustionJfrEvent`| Pool exhausted — trigger for load shedding      |
| `Exeris Kernel / Memory`      | `LeakDetectedEvent`                          | Unclosed `LoanedBuffer` — always a bug          |
| `Exeris Kernel / Security`    | `PrincipalBoundEvent`                        | Successful auth + scope bind                    |
| `Exeris Kernel / Security`    | `SecurityContextMissing`                     | Gate drop — token invalid or no provider        |
| `Exeris Kernel / Transport`   | `TransportBindEvent`                         | Transport successfully bound on port            |
| `Exeris Kernel / Bootstrap`   | `KernelBootstrapEvent`                       | Per-subsystem init duration                     |
| `Exeris Kernel / Crypto`      | `TlsHandshakeEvent`                          | Handshake duration + cipher suite               |
| `Exeris Kernel / Crypto`      | `TlsHandshakeFailureEvent`                   | Handshake failure + peer address                |

### Checking for Heap Allocations on the Hot Path

The TCK automatically validates this during `mvn install`. For manual investigation:

```bash
# Run with GC allocation profiling
java -XX:StartFlightRecording=settings=profile \
     --enable-preview \
     -jar exeris-kernel-core/target/exeris-kernel-core.jar
```

In JMC, open **Memory** → **Allocation by Thread**. The `wrap()`/`unwrap()` Virtual Thread should
show **0 B/op** on the TLS cipher path — this applies to **both Community and Enterprise** tiers,
because both share the same Core Panama FFM / OpenSSL engine (`CoreOpenSslLoader`, `NativeCipherContext`)
per ADR-008. Any allocation in `eu.exeris.kernel.core.crypto.*` or `eu.exeris.kernel.community.*`
on the TLS cipher hot path is a regression in both tiers.

The remaining heap allocation in Community occurs at the **JDBC layer** (`eu.exeris.kernel.community.persistence.*`)
and is expected — `ResultSet`, DTO, and `String` objects are heap-bound by the JDBC contract.
Enterprise eliminates this via native off-heap DB drivers.

---

## Architectural Guardrails (The Wall)

Before submitting a PR, verify the following:

### Banned Patterns (L0 Enforcement)

| Pattern                                  | Why banned                                           | What to use instead               |
|:-----------------------------------------|:-----------------------------------------------------|:-----------------------------------|
| `ThreadLocal`                            | Memory leaks with Virtual Threads                    | `ScopedValue` (JEP 506)            |
| `ExecutorService` / `Executors`          | Unstructured concurrency, orphan threads             | `StructuredTaskScope` (JEP 525)    |
| `CompletableFuture`                      | Unstructured concurrency                             | `StructuredTaskScope`              |
| `ByteBuffer` on the cipher path          | Allocates wrapper objects per record                 | `LoanedBuffer` + `MemorySegment`   |
| `Arena.ofConfined()` in business logic   | Bypasses `WatermarkManager`                          | `MemoryAllocator.allocate()`       |
| `sun.misc.Unsafe`                        | Unsafe, no arena bounds checking                     | FFM API / `VarHandle`              |
| Spring / Guice / Jakarta Inject          | Magic DI, reflection, class churn                    | Pure constructors + `ServiceLoader`|
| `String.formatted()` in exceptions       | Allocates `StringBuilder` on failure path            | `rawArgs[]` primitive layout       |

### The Wall (Module Boundaries)

- `exeris-kernel-spi` must import **nothing** outside `java.*` and `jdk.*`.
- `exeris-kernel-core` must import **nothing** from `community` or `enterprise`.
- `exeris-kernel-community` must import **nothing** from `core` internals (only SPI).
- If you add a new `ExerisKernelException` subclass, you **must** add a `rawArgs` layout
  comment and register the error code in `KernelErrorCodes.java`.
- If you add a new SPI interface, you **must** add a corresponding `Abstract*Tck` class
  in `exeris-kernel-tck` before the PR is mergeable.

### Every New Feature Requires the Test Triad

1. **Unit test** — verifies the class in isolation.
2. **Integration test** — verifies interaction with adjacent components.
3. **TCK expansion** — verifies the SPI contract holds for both Community and Enterprise.

A PR that only adds unit tests is **incomplete** if it touches an SPI boundary.

---

## Getting Help

- **Architecture decisions:** Read `docs/adr/` — specifically ADR-007 (Runtime) and ADR-008 (TLS).
- **Subsystem contracts:** Read `docs/subsystems/` for the specific domain you are touching.
- **Performance contract:** Read `docs/performance-contract.md` before touching any hot path.
- **Error codes:** `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java`
  is the single source of truth — do not add string literals in exception constructors.

---

## Off-Heap Memory — Critical Contributor Rules

Every contributor touching `MemoryAllocator`, `LoanedBuffer`, or `MemorySegment` MUST understand these rules.
Violations do not produce obvious Java exceptions — they cause silent leaks, `SIGSEGV`s, or `FAILED` state.

### Rule 1: Silent Leaks — Always use `try-with-resources`

A `LoanedBuffer` that is never `close()`d does **not** throw `OutOfMemoryError`. It silently exhausts
the off-heap budget until `WatermarkManager` signals `EX-MEM-1001`. Always:

```java
try (LoanedBuffer buffer = allocator.allocate(AllocationHint.MEDIUM)) {
    // use buffer
}  // close() called automatically — ref-count decremented
```

### Rule 2: Double-Free Causes SIGSEGV

Calling `LoanedBuffer.close()` twice decrements `refCount` below zero. The allocator may re-issue
the same slab. The old holder's `MemorySegment` address is now reused — the next `segment().address()`
call is a use-after-free. On Enterprise tier: `SIGSEGV`. Write all tests with `LeakDetectionMode.PARANOID`.

### Rule 3: `retain()` Before `StructuredTaskScope.fork()`

```java
buffer.retain();   // refCount +1 BEFORE forking
scope.fork(() -> {
    try {
        return process(buffer);
    } finally {
        buffer.close();  // refCount -1 in subtask
    }
});
```

Forgetting `retain()` means the parent scope may `close()` the buffer before the subtask reads it.

### Rule 4: `Arena.ofConfined()` is Banned in Business Logic

Never open an Arena directly. Use `MemoryAllocator.allocate(AllocationHint)`. Direct Arena usage
bypasses `WatermarkManager` and breaks the Zero-Allocation Covenant.

---

## Debugging Native Calls (Glass-Box JFR Workflow)

Standard JDWP debugger does not step into Panama FFM `downcall` frames (OpenSSL, `io_uring`).
The debugging workflow for native failures:

1. **Enable JFR with Exeris event profile:**
   ```bash
   jcmd <pid> JFR.start name=debug settings=profile filename=debug.jfr
   ```
2. Reproduce the failure.
3. Dump and open in JDK Mission Control:
   ```bash
   jcmd <pid> JFR.stop name=debug
   jmc debug.jfr
   ```
4. In JMC Event Browser → `Exeris / Crypto` → look for `TlsHandshakeFailureEvent`.
   The `nativeErrorCode` field is the OpenSSL error code.
5. Decode the error:
   ```bash
   openssl errstr <nativeErrorCode>
   # Example: openssl errstr 0x1416F086
   # → SSL routines:tls_process_server_certificate:certificate verify failed
   ```
6. For `io_uring` failures: inspect `cqe.res` (negative errno) in carrier loop JFR events.

---

## LazyConstant Pattern (JEP 526)

For singleton config caches and expensive one-time initialisations, use `LazyConstant.of(...)`.
Do NOT use double-checked locking (`volatile` + `if (field == null)`) — it is banned.

```java
// ✅ CORRECT: JVM constant-folding eligible
private static final LazyConstant<KernelSettings> SETTINGS =
        LazyConstant.of(() -> KernelSettings.load());

// ❌ BANNED: manual DCL, volatility, synchronization noise
private static volatile KernelSettings settings;
public static KernelSettings get() {
    if (settings == null) {
        synchronized (KernelSettings.class) {
            if (settings == null) settings = KernelSettings.load();
        }
    }
    return settings;
}
```
