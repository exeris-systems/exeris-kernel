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
    fixture.start(exchange -> exchange.respond(200, "ok"));
    int port = fixture.boundPort();
    // drive a real client at 127.0.0.1:port
}
```

`close()` is a **hard stop**, not a graceful drain — it releases the boot and joins. Since v0.11 the
underlying `TransportEngine.stop()` does drain in-flight streams (see
[`transport.md`](../subsystems/transport.md) → *Graceful-shutdown phase order*), so a request in flight
when the fixture closes completes rather than being severed.

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
  applied**. The engine ships its own DDL and applies it only when told to; `run.migrations` defaults
  to `false`, which is the step most easily missed when standing the engine up by hand and the reason a
  correctly-configured pool can still meet an empty database.
- `forJdbcUrl(url, runMigrations)` — for a container-backed Postgres or a pre-migrated schema.

**Which thread.** `engine()` is safe from the test thread: the Community engine reads no `ScopedValue`.
Consumer code that resolves kernel slots — the usual shape of a host runtime's transaction manager —
must go through `runInKernelScope(Runnable)`, which carries the work to the thread holding the boot.
A `ScopedValue` binding cannot outlive the frame that opened it, so the scope cannot be handed out;
work goes to it instead.

**What the consumer supplies.** A `PersistenceProvider` on the test classpath, and a JDBC driver for
the URL in use (`com.h2database:h2` for `inMemoryH2()`). The testkit declares neither: it references no driver
class, and a test library has no business putting a database on the classpath of everything downstream
of it.

### Security — `TestJwt`

Signed-token construction for tests exercising `TokenValidator` / `IdentityProvider` bindings, plus
the attack shapes those tests are built on: `expired()`, `tamperedSignature()`, `algNone()`,
`hmacConfusion()`, `noKid()`. Each is unit-tested against the plain, genuinely-valid token — a builder
that quietly produced a *valid* token when asked for `algNone()` would make every negative security
test pass while proving nothing.

---

## Coverage note

The two `KernelBootstrap*Fixture` classes are roughly half this module's lines and **cannot be covered
from inside it**: exercising them requires a provider on the classpath, and the module deliberately
declares none. Their coverage lives in `exeris-kernel-community`'s test scope, which JaCoCo attributes
to that module's bundle instead. The module clears its floor on the plumbing and `TestJwt`; expect the
ratio to fall as each new fixture lands, since every one adds uncoverable lines here and coverable
lines elsewhere.

---

## Shared plumbing

`SystemPropertySnapshot`, `FixtureThreads` and `FixtureBootLock` sit in the root package. All three are
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

Events, flow, graph, scheduling, storage, and telemetry have no fixtures. Consumers binding those SPIs
are still writing doubles, with the exposure described above. Tracked in
[`ROADMAP.md`](../ROADMAP.md) → *Testkit: No Real-Runtime Fixtures Outside HTTP*; persistence was taken
first because transactions are the sharpest case — propagation, rollback, and connection lifecycle are
data-integrity behaviour.

---

## See also

- [`03-community.md`](03-community.md) — the providers these fixtures boot.
- [`05-tck.md`](05-tck.md) — `Abstract*Tck` contract suites. Different job: the TCK verifies a
  *provider* against the contract; the testkit lets a *consumer* run against a real provider.
