---
title: "Implement a Provider"
type: howto
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Implement a Provider

**Audience:** you are implementing one of the kernel's SPI contracts — a driver, an engine, a
provider — either inside this repository or as your own module.

**Prerequisite:** [01 — Platform and Dependencies](./01-platform-and-dependencies.md) for the TCK
coordinates.

> **Verified against** `0.12.0` at commit `38208e69`, 2026-09-08. Every snippet below is
> quoted or minimally adapted from the cited file, and the citation is printed above it. If a
> snippet and its source disagree, **the source wins and this guide is the bug**. Citations name
> the file and the symbol to look for, never a line number — line numbers rot and symbols don't.

---

## What implementing a provider means

You implement a root interface in `exeris-kernel-spi`. Most of them — 16 of the 17 — are found
through `java.util.ServiceLoader` at bootstrap, which means registering the class in a
`META-INF/services` file. One is not: see *The contract that is not ServiceLoader-discovered* below,
and check which kind yours is before you write that file.

There is no dependency injection, and that is enforced rather than encouraged — the
`noDiFrameworksInSpi` rule in
`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/arch/ExerisArchitectureTest.java` fails the
build if a Spring, Guice, or `jakarta.inject` type appears in SPI, *"because Zero-Magic DI: use pure
constructors and ServiceLoader."*

---

## The 16 ServiceLoader-discovered root interfaces

Each is registered under its fully-qualified name in `META-INF/services/`. This inventory is the
contents of `exeris-kernel-community/src/main/resources/META-INF/services/` read on 2026-09-08.

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
| `websocket.WebSocketProvider` | `CommunityWebSocketProvider` | ADR-084 |

`websocket.WebSocketProvider` is the newest of the sixteen (landed for 0.12.0) and there is no
`docs/subsystems/websocket.md` yet — the diagnostics row above has carried an ADR link instead of a
subsystem doc for the same reason since before this guide existed.

Check [`docs/stability-matrix.md`](../stability-matrix.md) before you build on one — some of these
surfaces are `preview` and may still move. `websocket` in particular is `preview` for a stated
reason, not the default one — read the matrix entry before treating it as stable.

### The contract that is not ServiceLoader-discovered

`security.identity.IdentityProvider`
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/security/identity/IdentityProvider.java`,
ADR-040) is a full root contract — it has its own `AbstractIdentityProviderTck` and a Community
binding in `CommunityOidcIdentityProviderTckTest` — but it appears in **no** `META-INF/services`
file, and writing one for it accomplishes nothing.

It is selected per-token instead of per-boot, so a static classpath scan is the wrong mechanism.
`IdentityProviderRegistry` (a `@FunctionalInterface`, built through its `of(List<IdentityProvider>)`
factory) picks **exactly one** provider: highest `priority()` wins, ties resolve by registration
order, and the first candidate whose `canAttempt(rawToken)` returns `true` is selected
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/security/identity/IdentityProviderRegistry.java`).
The `SecurityProvider` *interface* has no knowledge of the registry at all — it is
`CommunitySecurityProvider`, the ServiceLoader-discovered Community implementation of that interface
(`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/security/CommunitySecurityProvider.java`),
that builds one in its constructor and dispatches `authenticate` into it. An out-of-tree
`SecurityProvider` is free to select identity providers a different way; the registry is Community's
chosen mechanism, not a requirement the SPI states.

The dispatch is fail-closed by contract, and that constrains your implementation: if the selected
provider's `authenticate` fails, the caller must **not** re-select another provider for the same
token. Re-dispatch on failure is token-confusion — a token its rightful issuer rejected getting
accepted by a laxer provider. If no provider claims the token, the registry returns `null` and the
dispatcher maps that to a terminal `EX-SEC-2002` deny.

So before writing artifact 3 below, check which kind of contract yours is. The four-artifact recipe
is right for the 16 above; for `IdentityProvider` the third artifact is registry wiring, not a
services file.

---

## The four artifacts

A ServiceLoader-discovered provider is exactly four things. **Omitting the third or fourth is the standard failure**: the
code compiles, the tests you wrote pass, and the kernel never loads your class.

1. The **SPI interface** you implement.
2. Your **implementation class**.
3. A **`META-INF/services` registration file**.
4. A **TCK binding test**.

The worked example below is `CommunityTelemetryProvider` — a small, complete provider-and-TCK pair
(not the smallest; `CommunityJobSchedulerProvider`'s pair is roughly half its size).

### 1. The SPI interface

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/telemetry/TelemetryProvider.java`,
the `TelemetryProvider` interface body (javadoc elided).

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

Source: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/telemetry/CommunityTelemetryProvider.java`,
the `CommunityTelemetryProvider` class (javadoc, the explicit no-arg constructor, and
`closeCreatedSinks`'s body elided).

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

- **`public final class`** with a public no-arg constructor — `CommunityTelemetryProvider` now writes
  it out explicitly, with a javadoc comment explaining it exists for `ServiceLoader`, rather than
  leaving it implicit. `ServiceLoader` requires the no-arg constructor either way; if you add a
  constructor with arguments and no no-arg one, discovery fails at runtime, not at compile time.
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

The contract is documented on `SubsystemProvider.getSubsystems(ConfigProvider)` itself
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/SubsystemProvider.java`):
the factory method must be pure — no side effects, no I/O, no locks, and it must not open a socket,
pool or file — implementations must have a public no-arg constructor, as `ServiceLoader` requires,
and a single call must not return two subsystems sharing one `Subsystem#name()`. A name collision
*across* providers is not an error at this level: the registry is keyed by name and keeps the first
entry it sees, so a lower-priority provider's subsystem is silently dropped rather than rejected —
see *Two selection rules* below.

### 4. The TCK binding test

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/telemetry/CommunityTelemetryProviderTckTest.java`,
the `CommunityTelemetryProviderTckTest` class (quoted — this is the entire class body, javadoc elided).

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

The convention, quoted from `EventProvider#priority()`'s javadoc in
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/events/EventProvider.java`:

```
 * Convention:
 *   Community: 0
 *   Enterprise: 100
 *   Test/Noop: -1
```

> **A real value in this repository does not fit that table, and copying it as a tier is wrong.**
> `KafkaEventProvider.PRIORITY = 50`
> (`exeris-kernel-community-kafka/src/main/java/eu/exeris/kernel/community/kafka/KafkaEventProvider.java`).
> Its own comment says why: *"above in-memory Community (0) so Kafka wins ServiceLoader, and below
> the Enterprise tier slot (100). Intra-Community precedence, not a tier value."* Use values
> between the tier slots to order providers *within* a tier — do not read 50 as a tier of its own.

### Two selection rules, and they differ

- **Most providers** — `BootstrapProviderSelector.loadHighestPriority(...)` in
  `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/BootstrapProviderSelector.java`:
  highest priority wins, filtered by an availability predicate, with a deterministic class-name
  tie-break (`selectFrom`'s `deterministicComparator`) so two equal-priority providers never resolve
  at random.
- **Subsystems** — `SubsystemRegistryLoader.loadRegistry(...)` in
  `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/SubsystemRegistryLoader.java`:
  providers are sorted by priority (with a moduleName, then class-name tie-break), then **first write
  wins per subsystem name** (`registry.putIfAbsent`). A lower-priority provider can still contribute a
  subsystem that no higher-priority provider claimed.

Community also carries its own copy of the selector, `CommunityProviderDiscovery.highestPriority(...)`,
for its internal wiring
(`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/CommunityProviderDiscovery.java`).

---

## If your provider owns a subsystem

A provider that needs lifecycle — something to start and stop with the kernel — implements
`Subsystem` and exposes it through a `SubsystemProvider`.

Contract (`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/Subsystem.java`):

| Method | Purpose |
|:--|:--|
| `name()` | unique identity; what `BootstrapSelector.forNames` matches |
| `dependsOn()` | names that must reach `INITIALIZED` (then `RUNNING`) first |
| `phase()` | `FOUNDATION` / `SERVICES` / `RUNTIME` |
| `initialize()` | phase 1 — resolve providers, allocate |
| `start()` | phase 2 — bind sockets, begin work |
| `stop()` | phase 3 — graceful shutdown |
| `isRunning()` | default-implemented health signal (default `false`) |
| `isOptional()` | whether `DEGRADE` policy may skip you (default `false`) |
| `providerBindings()` | `UnaryOperator<ScopedValue.Carrier>` — applied once, after every eligible subsystem's `initialize()` and before `start()` |

> **`stop()` must not throw.** Quoted from `Subsystem#stop()`'s javadoc in
> `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/Subsystem.java`:
> *"Implementations must not throw from this method, and must not leave a resource unreleased
> because a preceding release failed: shutdown has no second attempt and no failure policy — a
> subsystem that throws here is the last one the kernel hears from about that resource."* And, on
> what actually happens if you do: *"The Core orchestrator swallows any unchecked exception thrown
> here, logs it at `WARNING`, and continues with the next subsystem; the subsystem is then not
> marked `STOPPED`."*
> A throwing `stop()` does not fail loudly; it disappears into a log line while the resource stays
> open.

For the common shape — one subsystem resolving one provider into one `ScopedValue` slot — extend
`AbstractSingleProviderSubsystem`
(`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/AbstractSingleProviderSubsystem.java`)
rather than reimplementing discovery. Its own javadoc is explicit about when *not* to: persistence and
HTTP build engines and own lifecycles that are not "one provider, one slot", and deliberately do not
extend it.

---

## Errors your provider throws

Every kernel exception extends `ExerisKernelException` and carries a registered error code plus
**raw, unformatted arguments**. The reason is allocation discipline: failure paths must not build
strings. The banned-versus-correct contrast is written out in the class javadoc of
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/ExerisKernelException.java` itself
(look for the `// Correct` / `// Wrong` snippet) — read it once and the rule sticks.

Two files, always. First the code, in the single registry:

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java`,
the `EX_BOOT_3001` constant and its javadoc (quoted).

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

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/telemetry/TelemetryBootstrapException.java`,
the class javadoc and constructors (quoted; the descriptive paragraph, the `Error Code` section, and
the `@implNote`/`@since` tags are elided).

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
`SEC`, `GRPH`, `EVENT`, `FLOW`, `CFG`, `RUN`, `DIAG`, `BLOB`, `JOB` — plus one code that is not a
domain: `EX-UNK-0000`, stamped on a telemetry record that carried no error code at all, "the one code
that means 'the emitter did not say.'" Do not register a code under `UNK`; it exists so a decoder
always has something to put in the code field, not as a domain to grow. **Retired codes are never
reused**; `KernelErrorCodes.java` in
`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/` records exactly one retirement so
far — `EX-DIAG-1002`, with a comment explaining why the gap is kept rather than the number reused.

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
- **Test naming for a root-provider binding:** `Community<Thing>TckTest`, or `Community<Thing>TckIT`
  when the test needs Testcontainers — the `IT` suffix and its tag keep it out of the default build.
  Internal-collaborator TCK bindings (sinks, encoders) instead use `<Collaborator>TckTest` with no
  `Community` prefix — `JfrTelemetrySinkTckTest`, `Argon2idPasswordEncoderTckTest`.

---

## Binding the TCK

Add the TCK dependency (coordinates in
[01](./01-platform-and-dependencies.md#test-scope-coordinates)) — a plain `test`-scoped dependency
with no `classifier` or `type` as of `0.12.0`; the abstract suites live in the TCK module's *main*
sources at
`exeris-kernel-tck/src/main/java/eu/exeris/kernel/tck/contract/<subsystem>/Abstract*Tck.java` and
ship as that module's ordinary jar, not a `tests`-classified test-jar the way they did through
`0.11.x`.

Every abstract suite has the same shape: a `How to use` javadoc snippet, one or more `protected
abstract` factory methods, optional `protected` hooks you override to opt into extra assertions, a
`@BeforeEach final` setup, and `@Nested` groups.

**The minimum is: implement every abstract method.** Nothing more. The smallest real binding in the
repository is four lines of body:

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/transport/CommunityNativeTcpProviderTckTest.java`
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

> **The suite is what forces artifact 3 to exist.** `AbstractTelemetryProviderTck`'s `@Nested
> ServiceLoaderIntegration` group asserts the provider is discoverable on the classpath and that the
> highest-priority provider wins. A provider with no registration file compiles, passes its own unit
> tests, and fails here.

### Add your own assertions on top

The abstract suite deliberately under-constrains some things so that other tiers can implement them
differently. Where your binding has a stricter obligation, pin it locally:

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/bootstrap/CommunitySubsystemProviderTckTest.java`,
the `priorityIsCommunitySlot` test (quoted).

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

**Two suites, and which one can see your provider depends on where it lives.**
`ExerisArchitectureTest` (`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/arch/`) runs in a
module that depends only on the SPI, so every rule in it is an SPI rule whatever its name suggests —
it cannot see your driver. `KernelTierBanArchitectureTest`
(`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/`) carries the scoped bans across
SPI, Core and Community, which is where a Community provider is actually checked.

Rules in `ExerisArchitectureTest`, all SPI-scoped:

| Rule | What it forbids |
|:--|:--|
| `noJavaIoInSpi` | `java.io` in SPI — use `java.nio` or Panama FFM |
| `noFilesystemTypesInStorageSpi` | `java.nio.file` in the blob contract (ADR-056 §9) |
| `noStructuredTaskScopeInSchedulingSpi` | the last preview dependency, in scheduling SPI (ADR-057 §2) |
| `noExecutorsInSpi` | the `java.util.concurrent.Executors` factory class in SPI (not the `ExecutorService` interface itself) |
| `noCompletableFutureInSpi` | unstructured async in SPI |
| `noThreadLocalInSpi` | `ThreadLocal` in SPI — use `ScopedValue` |
| `noImplLeaksInSpi` | driver types in SPI (Netty, `io_uring`, OpenSSL, Hikari, `java.sql`, Nimbus, Kafka) |
| `noDiFrameworksInSpi` | Spring / Guice / `jakarta.inject` in SPI |
| `noDirectArenaInSpi` | ad-hoc `Arena` — allocate through `MemoryAllocator` |
| `noUnsafeInSpi` | `sun.misc.Unsafe` in SPI — use FFM |
| `streamingSpiCarriesNoWireOrTransportTypes` | `HttpStreamExchange` / `HttpStreamHandler` / `StreamEvent` reaching into Core HTTP, Community, transport SPI, or `jdk.jfr` (ADR-043) |
| `diagnosticsSpiIsEventFree` | the diagnostics SPI depending on the telemetry-spec or `jdk.jfr` packages (ADR-033 Obligation 10 / ADR-039) |

Rules in `KernelTierBanArchitectureTest`, reaching Core and Community as well:

| Rule | What it forbids |
|:--|:--|
| `noExecutors` | the `java.util.concurrent.Executors` factory class (not the `ExecutorService` interface itself) |
| `noCompletableFuture` | unstructured async |
| `noThreadLocal` | `java.lang.ThreadLocal` — use `ScopedValue` (note `ThreadLocalRandom` is a different type and is not banned) |
| `noUnsafe` | `sun.misc.Unsafe` — use FFM |

The last two `ExerisArchitectureTest` rules above are narrowly scoped — one to three named streaming
classes, one to the `spi.diagnostics` package — and easy to miss if you only skim the ban list for the
familiar ones (`noExecutorsInSpi` and friends). If your provider touches streaming exchanges or
diagnostics, read the rule's own `because(...)` text before assuming the familiar bans are the whole
list.

> A provider in `exeris-kernel-community-kafka` is checked by **neither**: nothing depends on that
> module, so it is on no suite's analysis classpath. Same for `exeris-kernel-diagnostics-cli`. Run the
> bans against your own module if you add one there.

Run it yourself; do not assume CI covered it:

```bash
mvn -q -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The reasoning behind each ban is in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) → *Architectural Guardrails (The Wall)*.

> **One documented tension, so you are not surprised by the poms.** Guidance describes Community as
> depending on SPI only, but `exeris-kernel-community/pom.xml` declares a compile dependency on
> `exeris-kernel-core` (its own comment there: `AbstractLoanedBuffer` lives in core). That is
> deliberate and reconciled in
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
- **The golden command:** `mvn clean install`. Checkstyle runs at the `validate` phase — before
  `compile`, so it also gates `mvn compile` and `mvn test`, not just `install` — but PMD's complexity
  check is bound to `verify`, which `compile` and `test` never reach. A `mvn test` that passes has
  not proven PMD-clean; only `mvn clean install` (or an explicit `mvn pmd:check`) has.

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
