# Contributing to Exeris Kernel

Welcome. This document is the **minimum viable onboarding guide** for contributors to
`exeris-kernel`. Read it before opening a PR — it will save you a review cycle.

The Exeris Kernel is not a standard Java application. It is a system-level runtime engineered
for zero-copy, zero-allocation hot paths. The patterns used here (Panama FFM, `LoanedBuffer`,
`ScopedValue`, off-heap memory) are deliberately non-standard. This document exists to lower
the barrier to contribution, not to enforce bureaucracy.

---

## Licence, contributor terms and provenance

**Licence.** [`LICENSE`](LICENSE) is the index; the tier files beside it are the operative texts —
[`LICENSE-COMMUNITY`](LICENSE-COMMUNITY) for everything in this repository and
[`LICENSE-ENTERPRISE`](LICENSE-ENTERPRISE) for the separately distributed Enterprise tier. Read the
file rather than a summary: the Community tier on this branch is not plain Apache-2.0.

**Sign-off.** An external contribution carries a `Signed-off-by:` trailer (`git commit -s`). It is a
Developer Certificate of Origin sign-off: it certifies that you have the right to submit the work
under the licence this repository publishes, and it grants nothing beyond that. Organisation members
are exempt from the trailer, not from being accountable for what they merge.

**Contributor agreement.** This is an open-core project, and a contribution the project may also
ship in the commercial tier needs a right that inbound-equals-outbound does not supply. That right
comes from a separate, non-exclusive contributor licence agreement. It does not transfer your
copyright, and it carries a promise back: your contribution stays available under the licence in
force on the day you submitted it. The agreement text and the signing flow are still being
finalised — until they exist a pull request is not blocked on them, but a contribution merged in the
meantime is merged on that understanding, and you will be asked to sign retroactively. If that is
not acceptable, say so on the pull request and it will be held.

**AI provenance.** Exeris is built with AI assistance as a matter of course, and states the terms
rather than hiding them. They are
[`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md),
and they are short:

- An AI-assisted commit keeps its `Co-authored-by:` trailer. Stripping it is a defect; adding it
  where no AI was involved is a lie, and is treated the same.
- **A named human is accountable for every line.** You must be able to explain and defend any part
  of the change in review. "The agent produced it" is not an answer — it is the reason the question
  is being asked.
- **Agents do not open pull requests, file issues or post comments without a human author.**
  Automated *review* comments are fine; automated *contributions* are not.
- A pull request states the commands run after the last push. A green default build says nothing
  about the tagged gates, and a skip-flagged build says nothing about lint.
- AI-generated tests that assert nothing observable are rejected. Tests follow this repository's
  philosophy — TCK-first, semantics over volume — not a line count.
- The description is what you would have written unaided: what it does, what it costs, what it does
  not cover. Not a transcript.

---

## Prerequisites

### Java Version

**JDK 25 LTS is required on this line.** The distributed artifact is preview-clean (ADR-066): main
sources compile without `--enable-preview`, and since the TCK's test-jar is the one published
artifact built from test sources, its fixtures are preview-clean too. The `preview` branch is the
opposite — newest JDK, preview features on — and ships separately as `1.0-preview`.

| Feature                            | JEP       | Status in JDK              | Used in Kernel                                    |
|:-----------------------------------|:----------|:---------------------------|:--------------------------------------------------|
| Virtual Threads                    | JEP 444   | Stable (JDK 21)            | Every request-handling path                       |
| Structured Concurrency             | GA / preview | **Track-dependent** — see ADR-066 | `StructuredScope` on `main`; `StructuredTaskScope.open(Joiner)` on the `preview` branch |
| Scoped Values                      | JEP 506   | Preview → finalising       | `KernelContext`, `StorageContext`, `PrincipalContext` |
| Foreign Function & Memory (FFM)    | JEP 454   | Stable (JDK 22)            | OpenSSL bindings, off-heap I/O, `io_uring`        |
| Flexible Constructor Bodies        | JEP 513   | **Closed / Delivered (JDK 25)** | Field pre-init before `super()` in value-ready types |
| Valhalla Value Classes (prep)      | JEP 401   | Early Access preview       | **Not yet used.** All data carriers (`record`, `final class`) are designed to be migration-ready: no `synchronized`, no `System.identityHashCode()`, no identity `==` on domain objects. C2 JIT Escape Analysis scalarises them on hot-paths today. |
| Lazy Constants                     | JEP 526   | Delivered in JDK 26 — **not available on this line** | Not used on `main`; the JDK 25 baseline predates it |

The root POM enables preview features for **test** sources only — `--enable-preview` on every
module's `default-testCompile` and on the surefire JVM ([pom.xml](pom.xml) lines 70, 91-94, 105) —
and never for main sources. Do not add it to a module's main sources on this line: the
Preview-Bytecode Gate reads the published jars and fails on any class stamped
`minor_version 0xFFFF`, which is exactly what a consumer would trip over.

The TCK is the exception that needs stating, because its test-jar *is* a published artifact: its
fixtures were moved off `StructuredTaskScope` so they carry no stamp. Other modules' test sources
still use it and still need the flag.

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
| Docker     | 24+             | Testcontainers-backed tests (Postgres, Kafka) |
| Podman     | 4.x (alternative) | Drop-in Docker replacement                 |
| `jcmd`     | bundled with JDK | JFR snapshot inspection                    |
| JDK Mission Control (JMC) | 9.0+ | Visual JFR analysis (optional)        |

---

## Static Analysis (SonarQube Cloud)

Analysis runs **from CI**, as a step in `build-and-verify` after `mvn clean verify -P coverage`, so it
consumes the compiled classes and JaCoCo XML that build already produced. Configuration lives in
`sonar-project.properties` (the CLI scanner reads it; note that the `sonar:sonar` Maven goal would *not*).

Two things must be true in the SonarQube Cloud project for this to work, and neither is expressible in
this repository. **Do them in this order** — the second is a precondition of the first, not a companion
to it:

1. **Automatic Analysis must be OFF.** It is mutually exclusive with CI-based analysis: leave it on and
   SonarQube Cloud *rejects* the CI submission. Requires an organisation administrator.
2. **`SONAR_TOKEN` repository secret.** Add it only once step 1 is done. Until then the analysis step
   skips (no token) rather than failing — which is the state you want, because with Automatic Analysis
   still enabled a submitted scan is rejected and the step fails.

The analysis step carries `continue-on-error: true` precisely so that this cannot escalate: it runs in
`build-and-verify`, which every other job depends on, and static analysis must never be able to block a
merge gate. Remove that flag once a CI analysis has been confirmed green, otherwise a rotting analysis
will go unnoticed.

Automatic Analysis is also why coverage was reported as `0.0% on New Code` before this setup regardless of
the tests written — it never builds the project, so no JaCoCo report exists for it to import. If you see
0.0% coverage on a pull request again, suspect the analysis path before suspecting the tests.

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
[INFO] BUILD SUCCESS
```
> `exeris-kernel-enterprise` is not part of this open-source reactor. It ships as a separate
> closed-source distribution. Running `mvn clean install` here will not build or require it.

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

**There is no local stack to start.** The repository has no `docker-compose.yml`, and nothing needs
one. Tests that require Postgres or Kafka start their own containers through Testcontainers and tear
them down again — so the only prerequisite is a running **Docker or Podman** daemon. This is a direct
consequence of ADR-001 (Cloud Agnostic Infrastructure): no cloud account, no managed service, no
port-forwarding magic.

Container images are pinned in the test sources, e.g.
`new PostgreSQLContainer<>("postgres:16")` in
`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/persistence/CommunityPersistenceIsolationLeakTckIT.java:37`.

### You do not need a daemon for the default build

Container-backed tests are tagged (`integration`, `continuity`, `stress`) and are **excluded from
`mvn clean install`**. A machine with no Docker daemon still gets a green default build — and that
green build is not evidence those tests pass. Run them explicitly when you touch what they cover:

```bash
mvn -pl <module> -DincludedGroups=integration -DexcludedGroups= test
```

The exclusion list is per-module — see the `excludedGroups` property in each module's `pom.xml`
(e.g. `exeris-kernel-community-kafka/pom.xml:31`) for which tags that module holds back and why.

### Consuming the kernel rather than contributing to it

Different requirements — notably no `--enable-preview` — and different coordinates. See
[docs/guides/01-platform-and-dependencies.md](docs/guides/01-platform-and-dependencies.md).

---

## JFR Inspection

Exeris emits custom JFR events under the `Exeris Kernel` category. They are the primary observability
mechanism — not log files.

### Starting a JFR Recording

```bash
# Attach to a running JVM (replace <pid> with the actual process ID)
jcmd <pid> JFR.start name=exeris-debug settings=profile duration=60s filename=debug.jfr

# Or start the JVM with recording enabled from the beginning:
java -XX:StartFlightRecording=name=boot,settings=profile,filename=boot.jfr \
     -jar exeris-kernel-core/target/exeris-kernel-core.jar
```

### Locating Exeris Events in JMC

1. Open `debug.jfr` in **JDK Mission Control (JMC)**.
2. Navigate to **Event Browser** → expand **Exeris Kernel**.
3. Key event categories:

| JFR Category                  | Event Class                                  | What it tells you                               |
|:------------------------------|:---------------------------------------------|:------------------------------------------------|
| `Exeris Kernel / Memory`      | `TelemetryJfrEvents.*`                       | Off-heap allocation sample (1% rate)            |
| `Exeris Kernel / Memory`      | `TelemetryJfrEvents.MemoryExhaustionJfrEvent`| Pool exhausted — trigger for load shedding      |
| `Exeris Kernel / Memory`      | `LeakDetectedEvent`                          | Unclosed `LoanedBuffer` — always a bug          |
| `Exeris Kernel / Security`    | `PrincipalBoundEvent`                        | Successful auth + scope bind                    |
| `Exeris Kernel / Security`    | `SecurityContextMissing`                     | Gate drop — token invalid or no provider        |
| `Exeris Kernel / Transport`   | `TransportBindEvent`                         | Transport successfully bound on port            |
| `Exeris Kernel / Bootstrap`   | `BootstrapJfrEvents.SubsystemInitializedEvent` | Per-subsystem initialization duration         |
| `Exeris Kernel / Crypto`      | `TlsHandshakeEvent`                          | Handshake duration + cipher suite               |
| `Exeris Kernel / Crypto`      | `TlsHandshakeFailureEvent`                   | Handshake failure + peer address                |

### Checking for Heap Allocations on the Hot Path

The TCK automatically validates this during `mvn install`. For manual investigation:

```bash
# Run with GC allocation profiling
java -XX:StartFlightRecording=settings=profile \
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
| `ExecutorService` / `Executors`          | Unstructured concurrency, orphan threads             | `StructuredScope` (`main`) / `StructuredTaskScope` (`preview`) |
| `CompletableFuture`                      | Unstructured concurrency                             | `StructuredScope` (`main`) / `StructuredTaskScope` (`preview`) |
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

If the TCK expansion asserts a **deny** or any other negative behaviour, it must also be shown to fail
against a binding that does not implement it — a negative case that would stay green regardless enforces
nothing. See [Proving a New Contract Case Is Not Vacuous](exeris-kernel-tck/README.md#proving-a-new-contract-case-is-not-vacuous).

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

### Rule 3: `retain()` Before Any `fork()`

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

## Compute-Once Config Pattern (Supplier + CAS)

For singleton config caches and expensive one-time initialisations, use `Supplier<T>` combined with
`AtomicReference` CAS — the same pattern modelled in the `ConfigProvider` SPI/TCK.
Do NOT use double-checked locking (`volatile` + `if (field == null)`) — it is banned.

```java
// ✅ CORRECT: SPI-aligned "compute once" cache using Supplier + CAS
private static final AtomicReference<KernelSettings> SETTINGS_REF = new AtomicReference<>();

public static KernelSettings settings() {
    KernelSettings current = SETTINGS_REF.get();
    if (current != null) {
        return current;
    }
    KernelSettings computed = KernelSettings.load();
    return SETTINGS_REF.compareAndExchange(null, computed) == null
            ? computed
            : SETTINGS_REF.get();
}

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
