---
title: "Module: `exeris-kernel-community-testkit`"
type: module
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Module: `exeris-kernel-community-testkit`

**Role:** fixtures that boot the **real** kernel for consumers outside this repository.
**Depends on:** SPI, Core. Deliberately **not** on `exeris-kernel-community` — see *Provider discovery* below.

---

## Why this module exists

A host runtime binding a kernel SPI has two options for testing: the real engine, or a double. Without
fixtures it gets the double by default — and a double encodes how its author *read* the contract, not
how the runtime *behaves*. Ordering, lifecycle, and threading are exactly the properties a double
cannot get wrong loudly, so defects in them surface in applications rather than in test suites.

That is not hypothetical. The v0.11 graceful-drain defect had the machinery present, the SPI
documenting it, and every in-repo test green — because the TCK asserted the state machine rather than
the semantics. Downstream reports keep arriving in the same shape.

These fixtures exist so the consumer never has to write the double.

---

## Provider discovery

No fixture imports a Community type. Each one boots through `KernelBootstrap` with a
`BootstrapSelector`, and pulls the engine out of the `KernelProviders` slot the bootstrap bound. The
module therefore compiles against SPI and Core alone, while booting **whatever provider the consumer's
classpath supplies** — Community, Enterprise, or a custom one.

The consequence is that the consumer supplies the provider. A fixture with no provider on the classpath
fails at `start()` with that stated as the likely cause, rather than yielding a half-booted kernel.

---

## Fixtures

### HTTP — `EmbeddedHttpEngineFixture`

Boots the `http` subsystem on a reserved loopback port with a caller-supplied `HttpHandler`.

```java
try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
    fixture.start(exchange -> exchange.respond(HttpStatus.OK));
    int port = fixture.boundPort();
    // drive a real client at 127.0.0.1:port
}
```

`close()` is a **hard stop**, not a graceful drain. It signals the boot thread to stop and joins it for
up to `KernelBootstrapHttpEngineFixture.STOP_TIMEOUT_SECONDS` (10 seconds), interrupting it if it is
still alive after that. `TransportEngine.stop()` does drain in-flight streams since v0.11 (see
[`transport.md`](../subsystems/transport.md) → *Graceful-shutdown phase order*), but that drain's own
deadline (`PaqsScheduler.DRAIN_DEADLINE_NANOS`, 60 seconds) is longer than the fixture's 10-second join
window — so a request in flight when the fixture closes completes only if it finishes within those 10
seconds, not the full drain budget the SPI otherwise allows.

### Persistence — `EmbeddedPersistenceEngineFixture` (since 0.11)

Boots the `persistence` subsystem — transitively pulling `memory` — against a JDBC URL.

```java
try (EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2()) {
    fixture.start();
    try (PersistenceConnection connection = fixture.engine().openConnection()) {
        connection.beginTransaction();
        connection.executeUpdate("...");
        connection.rollback();
    }
}
```

- `inMemoryH2()` — a fresh in-memory H2 in PostgreSQL-compatibility mode, unique per call, **migrations
  applied**. The engine ships its own DDL and applies it only when told to; `PersistenceSettings.runMigrations`
  defaults to `false`, which is the step most easily missed when standing the engine up by hand and the reason a
  correctly-configured pool can still meet an empty database.
- `forJdbcUrl(url, runMigrations)` — for a container-backed Postgres or a pre-migrated schema.

**Which thread.** `engine()` is safe from the test thread. Its `openConnection()` path does read two
`ScopedValue`s — `KernelProviders.STORAGE_CONTEXT` (via `storageContextOrSystem()`) and
`PersistenceSessionBox.REQUEST_SESSION` (via `currentOrNull()`) — but both fall back gracefully instead
of throwing when unbound (to `ImmutableStorageContext.GLOBAL`, and to `null`, respectively), so calling
from a thread with no bound scope is safe by design, not because the engine avoids `ScopedValue`.
Consumer code that resolves kernel slots — the usual shape of a host runtime's transaction manager —
must go through `runInKernelScope(Runnable)`, which carries the work to the thread holding the boot.
A `ScopedValue` binding cannot outlive the frame that opened it, so the scope cannot be handed out;
work goes to it instead.

**What the consumer supplies.** A `PersistenceProvider` on the test classpath, and a JDBC driver for
the URL in use (`com.h2database:h2` for `inMemoryH2()`). The testkit declares neither: it references no driver
class, and a test library has no business putting a database on the classpath of everything downstream
of it.

### Events and flow — `EmbeddedKernelFixture` (since 0.12)

Boots `events`, `flow`, or both — transitively pulling `persistence` and, for events, `memory` — and
hands back the engines out of **one** kernel.

```java
try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsAndFlowOnH2()) {
    fixture.start();
    fixture.eventEngine().bus().publish(descriptor, payload);
    // saga state written by fixture.flowEngine() is readable through:
    fixture.persistenceEngine();
}
```

Factories: `eventsOnH2()`, `flowOnH2()`, `eventsAndFlowOnH2()`, and
`forJdbcUrl(Set<String>, String, boolean)` for a container-backed database.

**One fixture over a subsystem set, not one fixture per subsystem** — and the reason is mechanical
rather than stylistic. Each fixture holds an entire `KernelBootstrap` open on its own thread, and
`FixtureBootLock` serialises boots because configuration travels through JVM-global system properties.
Three fixtures for one saga test would be three kernels, booted in sequence, sharing nothing. A saga
that emits an event needs both engines out of the *same* runtime, which is also how production wires
it.

`persistenceEngine()` is always available, because both subsystems declare `dependsOn("persistence")`.
That is what makes an assertion on saga state or on the outbox possible: the test reads the rows the
engine wrote. Asking for an engine whose subsystem was not selected fails with a message naming it,
not with a `NullPointerException`.

---

### Security — `TestJwt`

Signed-token construction for tests exercising `TokenValidator` / `IdentityProvider` bindings, plus
the attack shapes those tests are built on: `expired()`, `tamperedSignature()`, `algNone()`,
`hmacConfusion()`, `noKid()`. Each is unit-tested against the plain, genuinely-valid token — a builder
that quietly produced a *valid* token when asked for `algNone()` would make every negative security
test pass while proving nothing.

---

## Coverage note

The **three** `KernelBootstrap*Fixture` classes (`KernelBootstrapHttpEngineFixture`,
`KernelBootstrapPersistenceEngineFixture`, `KernelBootstrapRuntimeFixture`) are roughly half this
module's executable lines by JaCoCo's own count (239 of 506 LINE-counter lines, measured against this
tree) and their real boot path **cannot be exercised from inside this module**: doing so requires a
provider on the classpath, and the module deliberately declares none. A handful of their guard-clause
lines — the ones an "unstarted fixture refuses" test reaches without booting anything — do execute here,
which is why none of the three reads as literally 0% covered except the HTTP one.

Their real exercise lives in `exeris-kernel-community`'s test scope, which has both a provider and the
JDBC driver on its classpath — but that execution is **not** attributed to `exeris-kernel-community`'s
own JaCoCo bundle either: `jacoco:report` cross-references execution data only against class files
under the analysing module's own `target/classes`, and these classes are compiled into a different
module's jar. Running `exeris-kernel-community`'s consumer tests under `-P coverage` confirms this
directly — `eu/exeris/kernel/community/testkit/**` class names show up by the dozen in its raw
`jacoco.exec`, and not once in the `jacoco.xml` report generated from it. The exercise is real (that is
what the consumer tests in `exeris-kernel-community` prove) but it is invisible to every module's
reported coverage ratio, not "attributed elsewhere."

The module clears its own floor — the reactor default of 20% line coverage, since this module sets no
override — on the plumbing and `TestJwt`; expect the ratio to fall as each new `KernelBootstrap*Fixture`
lands, since every one adds unexercisable lines here without adding a counted line anywhere.

---

## Shared plumbing

`SystemPropertySnapshot`, `FixtureThreads`, `FixtureBootLock` and `KernelScopePump` sit in the root
package. All four are
**testkit-internal** — public only because Java package access does not reach across subpackages — and
none is fixture API. They exist because every fixture that holds a kernel boot open on a dedicated
thread has to publish configuration properties before booting and put them back afterwards, has to join
that thread on a deadline rather than hanging the suite, and must not boot while another fixture is
mid-boot.

### Concurrent starts

Kernel configuration arrives through system properties, which are JVM-global. The kernel reads them
**uncached** (`CommunityConfigProvider.resolveRaw` calls `System.getProperty` per lookup) during
subsystem initialisation, and `KernelBootstrap`'s `bootActive` guard is per-instance — so nothing in
the kernel serialises two `boot()` calls. Without a lock, two fixtures started from different threads
could each read the other's properties: for HTTP that is the wrong port, for persistence it is
**the wrong database**.

`FixtureBootLock` closes this by serialising the set-properties → boot → await-started window across
the whole fixture family. Two guarantees follow:

- **Fixtures are safe to use under parallel test execution.** They cannot boot simultaneously, but they
  run simultaneously — the lock covers starting, not the fixture lifetime.
- **Overlapping lifetimes are harmless.** The values are resolved once, into a configuration object the
  engine then owns, and never re-read; so one fixture restoring its snapshot while another is still
  running cannot affect the running one.

Each fixture also refuses a second concurrent `start()` on the same instance, rather than silently
spawning a second boot thread and leaking the first.

---

## Not yet covered

Graph, scheduling, storage, and telemetry have no fixtures. Consumers binding those SPIs are still
writing doubles, with the exposure described above. Tracked in
[`ROADMAP.md`](../ROADMAP.md) → *Testkit: No Real-Runtime Fixtures Outside HTTP*.

The order was not arbitrary: persistence came first because transactions are the sharpest case
(propagation, rollback, connection lifecycle are data-integrity behaviour), then events and flow
because they compose with it over the same engine. Graph is next and is a different shape — its
Community driver is swappable, and only the SQL/PGQ backend needs persistence, while the Cypher one
needs a Neo4j container and so cannot be a fixture a consumer runs without Docker. Its PGQ DDL is also
not yet H2-clean: `TIMESTAMPTZ` in the edge table is unknown to H2 even in PostgreSQL mode, where the
standard `TIMESTAMP WITH TIME ZONE` spelling PostgreSQL also accepts works. That is a production
change, not a fixture one, which is why it did not ride along here.

---

## See also

- [`03-community.md`](03-community.md) — the providers these fixtures boot.
- [`05-tck.md`](05-tck.md) — `Abstract*Tck` contract suites. Different job: the TCK verifies a
  *provider* against the contract; the testkit lets a *consumer* run against a real provider.
- [`guides/02-build-an-application.md`](../guides/02-build-an-application.md) — the consumer-side
  path these fixtures serve: booting the kernel, serving HTTP, then testing it.
