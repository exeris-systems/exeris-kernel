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

Signed-token construction for tests exercising `TokenValidator` / `IdentityProvider` bindings.

---

## Shared plumbing

`SystemPropertySnapshot` and `FixtureThreads` sit in the root package. Both are **testkit-internal** —
public only because Java package access does not reach across subpackages — and neither is fixture API.
They exist because every fixture that holds a kernel boot open on a dedicated thread has to set
configuration properties before booting and put them back afterwards, and has to join that thread on a
deadline rather than hanging the suite.

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
