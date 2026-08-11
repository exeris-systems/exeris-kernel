# Implement a Provider

**Audience:** you are implementing one of the kernel's SPI contracts — a driver, an engine, a
provider — either inside this repository or as your own module.

**Prerequisite:** [01 — Platform and Dependencies](./01-platform-and-dependencies.md) for the TCK
coordinates.

> **Verified against** `0.11.0-SNAPSHOT` at commit `1b93bf65`, 2026-08-11. Every snippet below is
> quoted or minimally adapted from the cited file, and the citation is printed above it. If a
> snippet and its source disagree, **the source wins and this guide is the bug**.

---

## What implementing a provider means

You implement one of 15 root interfaces in `exeris-kernel-spi`, register it in a
`META-INF/services` file, and the kernel finds it through `java.util.ServiceLoader` at bootstrap.

There is no dependency injection, and that is enforced rather than encouraged —
`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/arch/ExerisArchitectureTest.java:107-113`
fails the build if a Spring, Guice, or `jakarta.inject` type appears in SPI, *"because Zero-Magic DI:
use pure constructors and ServiceLoader."*

---

## The 15 root provider interfaces

Each is registered under its fully-qualified name in `META-INF/services/`. This inventory is the
contents of `exeris-kernel-community/src/main/resources/META-INF/services/` read on 2026-08-11.

| SPI interface (`eu.exeris.kernel.spi.…`) | Community implementation | Contract doc |
|:--|:--|:--|
| `bootstrap.SubsystemProvider` | `CommunitySubsystemProvider` | [bootstrap](../subsystems/bootstrap.md) |
| `config.ConfigProvider` | `CommunityConfigProvider` | [config](../subsystems/config.md) |
| `crypto.KernelCryptoProvider` | `CommunityKernelCryptoProvider` | [crypto](../subsystems/crypto.md) |
| `diagnostics.KernelDiagnosticsProvider` | `CommunityKernelDiagnosticsProvider` | ADR-033 |
| `events.EventProvider` | `CommunityEventProvider` | [events](../subsystems/events.md) |
| `flow.FlowProvider` | `CommunityFlowProvider` | [flow](../subsystems/flow.md) |
| `graph.GraphProvider` | `CommunityGraphProvider` | [graph](../subsystems/graph.md) |
| `http.HttpProvider` | `CommunityHttpProvider` | [http](../subsystems/http.md) |
| `memory.MemoryProvider` | `CommunityMemoryProvider` | [memory](../subsystems/memory.md) |
| `persistence.PersistenceProvider` | `CommunityPersistenceProvider` | [persistence](../subsystems/persistence.md) |
| `scheduling.JobSchedulerProvider` | `CommunityJobSchedulerProvider` | [scheduling](../subsystems/scheduling.md) |
| `security.SecurityProvider` | `CommunitySecurityProvider` | [security](../subsystems/security.md) |
| `storage.blob.BlobStorageProvider` | `CommunityFilesystemBlobStorageProvider`, `CommunityS3BlobStorageProvider` | [storage](../subsystems/storage.md) |
| `telemetry.TelemetryProvider` | `CommunityTelemetryProvider` | [telemetry](../subsystems/telemetry.md) |
| `transport.TransportProvider` | `NativeTcpTransportProvider` | [transport](../subsystems/transport.md) |

Check [`docs/stability-matrix.md`](../stability-matrix.md) before you build on one — some of these
surfaces are `preview` and may still move.

---

## The four artifacts

Every provider is exactly four things. **Omitting the third or fourth is the standard failure**: the
code compiles, the tests you wrote pass, and the kernel never loads your class.

1. The **SPI interface** you implement.
2. Your **implementation class**.
3. A **`META-INF/services` registration file**.
4. A **TCK binding test**.

The worked example below is `CommunityTelemetryProvider` — the smallest complete provider-and-TCK
pair in the repository.

### 1. The SPI interface

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/telemetry/TelemetryProvider.java:33-58`
(quoted).

```java
public interface TelemetryProvider {

    /**
     * Creates all active sinks for this provider.
     * ...
     * @throws TelemetryBootstrapException if a required sink cannot be initialized
     */
    List<TelemetrySink> createSinks(TelemetryConfig config);

    /**
     * Display name used in bootstrap JFR events (e.g., {@code "ExerisEnterprise/BinaryGlassBox"}).
     */
    String providerName();

    /**
     * Higher value wins; Community = 0, Enterprise = 100.
     */
    default int priority() {
        return 0;
    }
}
```

### 2. The implementation

Source: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/telemetry/CommunityTelemetryProvider.java:36-81`
(quoted, `closeCreatedSinks` body elided).

```java
public final class CommunityTelemetryProvider implements TelemetryProvider {

    private static final String PROVIDER_NAME = "ExerisCommunity/TextTelemetry";

    @Override
    public List<TelemetrySink> createSinks(TelemetryConfig config) {
        List<TelemetrySink> sinks = new ArrayList<>(4);
        try {
            if (config.jfrSinkEnabled()) {
                sinks.add(new JfrTelemetrySink());
            } else {
                sinks.add(new Slf4jTelemetrySink());
            }
            // … console and file sinks, conditional on config …
        } catch (RuntimeException e) { //NOPMD AvoidCatchingGenericException — must close partial sinks
            closeCreatedSinks(sinks, e);
            throw new TelemetryBootstrapException(PROVIDER_NAME, "Sink creation failed", e);
        }
        return List.copyOf(sinks);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }
}
```

Three things here are house style, not incidental:

- **`public final class`** with an implicit public no-arg constructor. `ServiceLoader` requires the
  no-arg constructor; if you add a constructor with arguments and no default, discovery fails at
  runtime, not at compile time.
- **`PROVIDER_NAME` as a constant**, returned by `providerName()` and reused in the failure path —
  not a literal repeated at each site.
- **Partial construction is cleaned up before throwing.** If the third sink fails, the two already
  created are closed and their close-failures suppressed onto the original. A provider that
  half-initialises and throws leaks whatever it opened.

### 3. The registration file

File: `exeris-kernel-community/src/main/resources/META-INF/services/eu.exeris.kernel.spi.telemetry.TelemetryProvider`

```
eu.exeris.kernel.community.telemetry.CommunityTelemetryProvider
```

The filename is the fully-qualified interface name; the content is one fully-qualified
implementation class per line. Two implementations of one interface means two lines — as
`BlobStorageProvider` does for the filesystem and S3 drivers.

The contract is documented in the SPI itself
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/SubsystemProvider.java:36-45`):
the factory method MUST be pure — no side effects, no I/O, no locks — the returned list MUST NOT
contain duplicates, and implementations MUST have a public no-arg constructor.

### 4. The TCK binding test

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/telemetry/CommunityTelemetryProviderTckTest.java:30-47`
(quoted — this is the entire class).

```java
@DisplayName("Community: CommunityTelemetryProvider TCK")
class CommunityTelemetryProviderTckTest extends AbstractTelemetryProviderTck {

    @Override
    protected TelemetryProvider createProvider() {
        return new CommunityTelemetryProvider();
    }

    @Override
    protected boolean expectStandardJfrSinkWhenEnabled() {
        return true;
    }

    @Override
    protected boolean expectSlf4jFallbackWhenJfrDisabled() {
        return true;
    }
}
```

That is the whole binding. You supply a factory; the abstract suite supplies the assertions.

---

## Discovery, priority, and who wins

Every root interface declares `default int priority()` returning `0`. When several providers for the
same interface are on the classpath, the highest wins.

The convention, quoted from
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/events/EventProvider.java:66-72`:

```
 * Convention:
 *   Community: 0
 *   Enterprise: 100
 *   Test/Noop: -1
```

> **A real value in this repository does not fit that table, and copying it as a tier is wrong.**
> `KafkaEventProvider.PRIORITY = 50`
> (`exeris-kernel-community-kafka/src/main/java/eu/exeris/kernel/community/kafka/KafkaEventProvider.java:41-45`).
> Its own comment says why: *"above in-memory Community (0) so Kafka wins ServiceLoader, and below
> the Enterprise tier slot (100). **Intra-Community precedence, not a tier value.**"* Use values
> between the tier slots to order providers *within* a tier — do not read 50 as a tier of its own.

### Two selection rules, and they differ

- **Most providers** — `BootstrapProviderSelector.loadHighestPriority(...)`
  (`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/BootstrapProviderSelector.java:65-100`):
  highest priority wins, filtered by an availability predicate, with a deterministic class-name
  tie-break so two equal-priority providers never resolve at random.
- **Subsystems** — `SubsystemRegistryLoader`
  (`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/SubsystemRegistryLoader.java:66-95`): providers are sorted by
  priority, then **first write wins per subsystem name** (`putIfAbsent`). A lower-priority provider
  can still contribute a subsystem that no higher-priority provider claimed.

Community also carries its own copy of the selector for its internal wiring
(`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/CommunityProviderDiscovery.java:39-46`).

---

## If your provider owns a subsystem

A provider that needs lifecycle — something to start and stop with the kernel — implements
`Subsystem` and exposes it through a `SubsystemProvider`.

Contract (`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/Subsystem.java`):

| Method | Line | Purpose |
|:--|:--|:--|
| `name()` | 65 | unique identity; what `BootstrapSelector.forNames` matches |
| `dependsOn()` | 90 | names that must be `RUNNING` first |
| `phase()` | 100 | `FOUNDATION` / `SERVICES` / `RUNTIME` |
| `initialize()` | 112 | phase 1 — resolve providers, allocate |
| `start()` | 123 | phase 2 — bind sockets, begin work |
| `stop()` | 133 | phase 3 — graceful shutdown |
| `isRunning()` | 141 | default-implemented health signal |
| `isOptional()` | 154 | whether `DEGRADE` policy may skip you |
| `providerBindings()` | 214 | `UnaryOperator<ScopedValue.Carrier>` — called after `initialize()`, before `start()` |

> **`stop()` must not throw.** Quoted from `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/Subsystem.java:128-132`:
> *"The orchestrator calls `stop()` in reverse topological order so that dependents are always
> stopped before their dependencies. Implementations MUST NOT throw from this method — exceptions
> are caught and logged as WARN by the orchestrator."*
> A throwing `stop()` does not fail loudly; it disappears into a log line while the resource stays
> open.

For the common shape — one subsystem resolving one provider into one `ScopedValue` slot — extend
`AbstractSingleProviderSubsystem`
(`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/AbstractSingleProviderSubsystem.java:62-92`)
rather than reimplementing discovery.

---

## Errors your provider throws

Every kernel exception extends `ExerisKernelException` and carries a registered error code plus
**raw, unformatted arguments**. The reason is allocation discipline: failure paths must not build
strings. The banned-versus-correct contrast is written out at
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/memory/MemoryExhaustedException.java:33-45`
— read it once and the rule sticks.

Two files, always. First the code, in the single registry:

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java:145-154`
(quoted).

```java
/**
 * Telemetry provider failed to initialise one or more sinks.
 *
 * <p><b>rawArgs layout for Glass-Box:</b>
 * <ul>
 *   <li>index 0 – {@code String} providerName</li>
 *   <li>index 1 – {@code String} reason</li>
 * </ul>
 */
public static final String EX_BOOT_3001 = "EX-BOOT-3001";
```

Then the exception, mirroring that layout in its own javadoc:

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/telemetry/TelemetryBootstrapException.java:14-36`
(quoted).

```java
/**
 * Thrown when the {@link eu.exeris.kernel.spi.telemetry.TelemetryProvider} cannot initialise one or more sinks.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → String providerName  (which provider failed to initialise)
 * index 1 → String reason        (failure cause — static constant, never formatted)
 * </pre>
 */
public final class TelemetryBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Telemetry provider bootstrap failed";

    public TelemetryBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, null, providerName, reason);
    }

    public TelemetryBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, cause, providerName, reason);
    }
}
```

The invariants: a **static `MESSAGE` constant** with no interpolation; the code referenced through
the `KernelErrorCodes` constant, never a string literal; and the **rawArgs layout documented in both
places**, because the binary Glass-Box decoder reads by index and a silent reordering corrupts every
decoded frame.

Codes are `EX-[DOMAIN]-[4 digits]`. There are 14 domains — `MEM`, `BOOT`, `NET`, `HTTP`, `PERS`,
`SEC`, `GRPH`, `EVENT`, `FLOW`, `CFG`, `RUN`, `DIAG`, `BLOB`, `JOB`. **Retired codes are never reused**;
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java:893` records one retirement explicitly rather than freeing the number.

---

## Naming and packaging

- **Package:** `eu.exeris.kernel.community.<subsystem>`, mirroring the SPI's package segment
  (`spi.telemetry` → `community.telemetry`).
- **`Community*` prefix** on the root SPI-implementing class only. Internal collaborators are not
  prefixed — `JfrTelemetrySink`, `Argon2idPasswordEncoder`, `NativeTcpCarrier`.
- **Two existing classes break the prefix rule**: `NativeTcpTransportProvider` and
  `KafkaEventProvider`. They are precedent for descriptive naming where the driver identity matters
  more than the tier — not a licence to skip the prefix by default.
- **`providerName()`** is formatted `Tier/Component` — `"ExerisCommunity/TextTelemetry"`,
  `"ExerisCommunityKafka/Events"`. **`providerId()`** is kebab-case — `"community-transport"`,
  `"blob-fs-community"`.
- **Test naming:** `Community<Thing>TckTest`, or `Community<Thing>TckIT` when the test needs
  Testcontainers — the `IT` suffix and its tag keep it out of the default build.

---

## Binding the TCK

Add the TCK test-jar (coordinates in
[01](./01-platform-and-dependencies.md#test-scope-coordinates)); the abstract suites live in the TCK
module's *test* sources at
`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/contract/<subsystem>/Abstract*Tck.java`.

Every abstract suite has the same shape: a `How to use` javadoc snippet, one or more `protected
abstract` factory methods, optional `protected` hooks you override to opt into extra assertions, a
`@BeforeEach final` setup, and `@Nested` groups.

**The minimum is: implement every abstract method.** Nothing more. The smallest real binding in the
repository is four lines of body:

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/transport/CommunityNativeTcpProviderTckTest.java:15-22`
(quoted — the entire class).

```java
@DisplayName("Community: NativeTcpTransportProvider TCK")
class CommunityNativeTcpProviderTckTest extends AbstractTransportProviderTck {

    @Override
    protected TransportProvider createProvider() {
        return new NativeTcpTransportProvider();
    }
}
```

Effort varies a lot by contract: `AbstractTransportProviderTck` has one abstract method,
`AbstractSecurityProviderTck` has thirteen.

> **The suite is what forces artifact 3 to exist.** `AbstractTelemetryProviderTck:273-297` contains a
> `@Nested` ServiceLoader group asserting the provider is discoverable on the classpath and that the
> highest-priority provider wins. A provider with no registration file compiles, passes its own unit
> tests, and fails here.

### Add your own assertions on top

The abstract suite deliberately under-constrains some things so that other tiers can implement them
differently. Where your binding has a stricter obligation, pin it locally:

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/bootstrap/CommunitySubsystemProviderTckTest.java:26-33`
(quoted).

```java
@Test
@DisplayName("priority() == 0 (Community Open-Core slot, inherited from SPI default)")
void priorityIsCommunitySlot() {
    // Pins the Community tier slot explicitly — AbstractSubsystemProviderTck only enforces
    // priority() >= 0, so an accidental future override to the Enterprise slot (100) would
    // otherwise pass. Mirrors the config provider side's isEqualTo(0) discipline.
    assertThat(new CommunitySubsystemProvider().priority()).isEqualTo(0);
}
```

---

## The Wall — guards your code must pass

`ExerisArchitectureTest` (`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/arch/`) is an ArchUnit
suite, and these are the rules most likely to catch a new provider:

| Rule | Line | What it forbids |
|:--|:--|:--|
| `noJavaIoInSpi` | 38 | `java.io` in SPI — use `java.nio` or Panama FFM |
| `noFilesystemTypesInStorageSpi` | 45 | `java.nio.file` in the blob contract (ADR-056 §9) |
| `noStructuredTaskScopeInSchedulingSpi` | 55 | the last preview dependency, in scheduling SPI (ADR-057 §2) |
| `noExecutorsAnywhere` | 69 | `Executors` / `ExecutorService` anywhere |
| `noCompletableFuture` | 80 | unstructured async |
| `noThreadLocal` | 89 | `ThreadLocal` — use `ScopedValue` |
| `noImplLeaksInSpi` | 96 | driver types in SPI (Netty, Hikari, `java.sql`, Nimbus, Kafka) |
| `noDiFrameworksInSpi` | 107 | Spring / Guice / `jakarta.inject` in SPI |
| `noDirectArenaInSpi` | 116 | ad-hoc `Arena` — allocate through `MemoryAllocator` |
| `noUnsafe` | 123 | `sun.misc.Unsafe` — use FFM |

Run it yourself; do not assume CI covered it:

```bash
mvn -q -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The reasoning behind each ban is in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) → *Architectural Guardrails (The Wall)*.

> **One documented tension, so you are not surprised by the poms.** Guidance describes Community as
> depending on SPI only, but `exeris-kernel-community/pom.xml:81-84` declares a compile dependency on
> `exeris-kernel-core`. That is deliberate and reconciled in
> [`docs/modules/03-community.md`](../modules/03-community.md) as *"Controlled Core Access
> (ADR-008)"*. The pom is the reality; the "SPI only" phrasing is the aspiration for driver code.

---

## Gates before an in-repo PR merges

These apply to providers landing **in this repository**. An out-of-tree provider is bound only by
the TCK it chooses to run.

- **New SPI interface → an `Abstract*Tck` first.** `CONTRIBUTING.md`: *"If you add a new SPI
  interface, you must add a corresponding `Abstract*Tck` class in `exeris-kernel-tck` before the PR
  is mergeable."*
- **New negative TCK case → prove it is not vacuous.** A case that would also pass against a
  non-conforming implementation tests nothing. The procedure — a committed meta-test, or a recorded
  guard mutation — is in
  [`exeris-kernel-tck/README.md`](../../exeris-kernel-tck/README.md) → *Proving a New Contract Case
  Is Not Vacuous*.
- **The test triad:** unit + integration + TCK expansion. A PR touching an SPI boundary with only
  unit tests is incomplete.
- **The golden command:** `mvn clean install`. It is lint-gated; `mvn compile` and `mvn test` are not.

---

## Not available today

- **No archetype or scaffolding tool** for a new provider module. Copy the shape from
  `exeris-kernel-community-kafka`, which is the smallest standalone provider module in the reactor.
- **No out-of-tree provider is exercised in CI.** The ServiceLoader path is proven by in-repo
  bindings only, so a packaging-level problem specific to an external jar would not be caught here.
- **Not every `Abstract*Tck` has a Community binding.** Unbound suites are open contract debt, not a
  statement that the contract is optional.

---

## See also

- [01 — Platform and Dependencies](./01-platform-and-dependencies.md)
- [02 — Build an Application](./02-build-an-application.md)
- [`docs/modules/01-spi.md`](../modules/01-spi.md) — what belongs in SPI and why
- [`docs/modules/03-community.md`](../modules/03-community.md) — the Community tier's rules
- [`docs/modules/05-tck.md`](../modules/05-tck.md) — contract-verification architecture
- [`docs/stability-matrix.md`](../stability-matrix.md) — maturity of each SPI surface
- [`docs/subsystems/exceptions.md`](../subsystems/exceptions.md) — the full error-code contract
