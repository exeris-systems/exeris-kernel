# Build an Application on Exeris Kernel

**Audience:** you are writing an application and want the kernel to run it — boot it, serve HTTP,
configure it, and test it.

**Prerequisite:** [01 — Platform and Dependencies](./01-platform-and-dependencies.md). This page
assumes `eu.exeris:exeris-kernel-community` is on your classpath.

> **Verified against** `0.11.0-SNAPSHOT` at commit `1b93bf65`, 2026-08-11. Every snippet below is
> quoted or minimally adapted from the cited file, and the citation is printed above it. If a
> snippet and its source disagree, **the source wins and this guide is the bug**.

---

## What you are building

The kernel is a library that runs inside *your* JVM process, started from *your* `main()`. There is
no container to deploy into, no application server, and no framework that owns the lifecycle. You
call `KernelBootstrap`, it brings up the subsystems you asked for, and it hands control back to a
`Runnable` you supply.

---

## The one thing to get right

**`boot(Runnable)` is blocking, and the `Runnable` you pass it *is* your application's lifetime.**
When that `Runnable` returns, the kernel shuts down — in reverse-topological order, from a `finally`
block that always runs
(`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/KernelBootstrap.java:294-298`).

So a server must **park inside the lambda**. If you boot and return immediately, you have written a
program that starts a kernel and then stops it.

Source: `exeris-kernel-community-testkit/src/main/java/eu/exeris/kernel/community/testkit/http/KernelBootstrapHttpEngineFixture.java:192-196`
(adapted — the fixture's latch and `AtomicReference` plumbing removed).

```java
bootstrap.boot(() -> {
    // The kernel is up. Everything you do lives here.
    awaitShutdownSignal(stop);   // your own park — a latch, a queue take, whatever fits
});
// Control reaches here only after the kernel has shut down.
```

> **There is no signal handling.** From
> [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md):
> *"Signal handling (SIGTERM/SIGINT) is not yet implemented; callers are responsible for invoking
> `boot()` and managing JVM shutdown."*
> If you want Ctrl-C or `SIGTERM` to shut down cleanly, you register the hook that releases your
> park. The kernel will not do it for you.

---

## A minimal boot

`KernelBootstrap` is a builder. This is the only real `main()` in the repository:

Source: `exeris-kernel-diagnostics-cli/src/main/java/eu/exeris/kernel/diagnostics/cli/DiagnosticsCli.java:75-88`
(quoted).

```java
public static void main(String[] args) throws KernelBootstrap.BootstrapException {
    ObjectMapper mapper = newMapper();
    KernelBootstrap.builder()
            .selector(BootstrapSelector.all())
            .build()
            .inspect(() -> {
                DiagnosticsCli cli = new DiagnosticsCli(loadDiagnostics(), mapper);
                try {
                    cli.serve(System.in, System.out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
}
```

Note it calls **`inspect()`**, not `boot()`. The two differ in how far they take the kernel:

| | `boot(Runnable)` | `inspect(Runnable)` |
|:--|:--|:--|
| Resolves config + subsystem topology | yes | yes |
| Calls `initialize()` / `start()` | **yes** | **no** |
| Use for | running an application | introspecting what *would* boot |

`inspect()` is the zero-risk way to check your classpath resolves before you commit to a real boot.

Builder options (`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/KernelBootstrap.java:464-513`): `selector(...)`, `failurePolicy(...)`,
`classLoader(...)`, `build()`.

---

## Choosing which subsystems come up

`BootstrapSelector` decides what boots. **You never list dependencies by hand** — the orchestrator
expands the transitive closure for you.

```java
BootstrapSelector.all()                        // everything on the classpath
BootstrapSelector.none()                       // config scope only, no subsystems
BootstrapSelector.forNames("http")             // http + everything it depends on
BootstrapSelector.forNames("persistence", "events")
```

Source: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/BootstrapSelector.java:67, 81, 102`.

The Community subsystems, with what each declares:

| Subsystem | `dependsOn()` | `phase()` |
|:--|:--|:--|
| `memory` | — | FOUNDATION |
| `crypto` | `memory` | SERVICES |
| `security` | `memory` | SERVICES |
| `persistence` | `memory` | SERVICES |
| `transport` | `memory`, `crypto` | SERVICES |
| `graph` | `memory`, `persistence` | SERVICES |
| `scheduling` | — | SERVICES |
| `events` | `memory`, `persistence` | RUNTIME |
| `flow` | `persistence` | RUNTIME |
| `http` | `memory` | RUNTIME |

Source: the `name()` / `dependsOn()` / `phase()` methods of
`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/Community*Subsystem.java`
(read from source, 2026-08-11).

Phases run in order — `FOUNDATION(0)` → `SERVICES(1)` → `RUNTIME(2)` — and a subsystem in phase N
starts only after every subsystem in phase N-1 is `RUNNING`
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/BootstrapPhase.java:12-22`).

> **Note on [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md).** Its Diagram 1 predates
> the current Community set — it omits `security` and `scheduling` — and its `(parallel)` phase
> labels predate the v0.11 change described later in that same document, where phases start on the
> booting thread (ADR-066). The table above is the current set; read the diagram for the DAG
> concept, not the membership.

### Failure policy

`FAIL_FAST` is the default: any subsystem failing to start aborts the boot. `DEGRADE` continues past
failures, but only for subsystems that declare `isOptional()`, and never for `FOUNDATION`.

---

## Serving HTTP

### The rule: bind the handler *around* `boot()`, not inside it

Your handler reaches the HTTP engine through a `ScopedValue`,
`HttpKernelProviders.HTTP_SERVER_HANDLER`
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpKernelProviders.java:80`). The HTTP
subsystem reads it during `start()`.

That ordering is the whole trick. From `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/bootstrap/BootstrapPhase.java:16-22`:

> *"the Core orchestrator starts them in order on the booting thread (ADR-066), because a subsystem's
> `start()` must observe the `ScopedValue` bindings the application established around `boot()`, and
> those cannot be carried onto another thread."*

Bind it inside the lambda and the subsystem has already started without it.

Source: `exeris-kernel-community-testkit/src/main/java/eu/exeris/kernel/community/testkit/http/KernelBootstrapHttpEngineFixture.java:185-202`
(adapted — latch, `AtomicReference`, and failure capture removed).

```java
KernelBootstrap bootstrap = KernelBootstrap.builder()
        .selector(BootstrapSelector.forNames("http"))
        .build();

ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, handler)
        .run(() -> {
            try {
                bootstrap.boot(() -> awaitShutdownSignal(stop));
            } catch (KernelBootstrap.BootstrapException e) {
                throw new IllegalStateException(e);
            }
        });
```

**If you bind nothing**, the subsystem falls back to built-in health routes — `/health`,
`/health/live`, `/health/ready` — and every other path is unserved. A kernel that answers only
`/health` usually means the bind never happened.

### Writing a handler

`HttpHandler` is a `@FunctionalInterface` with one method, `void handle(HttpExchange)`
(`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpHandler.java:37-50`). On the exchange you get `request()`,
`pathParams()`, and `respond(...)` — which you must call **exactly once**.

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/testing/http/KernelBootstrapHttpEngineFixtureIntegrationTest.java:38-47`
(quoted).

```java
fixture.start(exchange -> {
    HttpResponse response = switch (exchange.request().path()) {
        case "/fixture" -> HttpResponse.noBody(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("X-Fixture-Handler", "active")));
        default -> HttpResponse.noBody(HttpStatus.NOT_FOUND, exchange.request().version());
    };
    exchange.respond(response);
});
```

`respond` has four forms (`exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpExchange.java:86-122`): `respond(HttpResponse)`,
`respond(HttpTypedResponse)`, `respond(HttpStatus, Object)` — which serialises the payload through
the response encoder — and `respond(HttpStatus)` for a bare status.

### Routing

You do not have to `switch` on paths. `HttpRouter` **is** an `HttpHandler`
(`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/http/routing/HttpRouter.java:51`), so it
drops into the same slot.

Source: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/testing/http/GeneratedAppBootPathReachabilityIntegrationTest.java:66-71, 104-111`
(quoted, two fragments joined).

```java
HttpRouter router = HttpRouter.builder()
        .route(GeneratedAppBootPathReachabilityIntegrationTest::respondWithCapturedId, "/x/{id}",
                HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)
        .route(HttpMethod.POST, "/x", GeneratedAppBootPathReachabilityIntegrationTest::decodeAndEcho)
        .build();

private static void respondWithCapturedId(HttpExchange exchange) {
    String id = exchange.pathParams().get("id");
    exchange.respond(HttpResponse.noBody(
            HttpStatus.OK,
            exchange.request().version(),
            List.of(new HttpHeader("X-Path-Id", id == null ? "" : id))));
}
```

Builder surface (`exeris-kernel-core/src/main/java/eu/exeris/kernel/core/http/routing/HttpRouter.java:235-315`): `route(method, path, handler)`,
`route(handler, path, methods...)`, `prefixRoute(method, prefix, handler)`,
`streamRoute(method, path, streamHandler)` for SSE, and `notFound(handler)`.

Resolution precedence is **exact → template → prefix**, with a HEAD→GET fallback.

---

## Configuration

There is no configuration file. Community resolves every key in this order:

1. System property `exeris.<key>` — e.g. `-Dexeris.http.port=8080`
2. Environment variable `EXERIS_<KEY>` — dots and dashes become underscores, uppercased: `EXERIS_HTTP_PORT=8080`
3. The compiled default

Source: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/config/CommunityConfigProvider.java:180-197`.

Inside your application, read config with `KernelProviders.CURRENT_CONFIG.get()`.

> **Two limits worth knowing before you design around config.**
> - **No file is read at startup.** The `DynamicConfigFileWatcher` in Core parses `.properties` files
>   for *hot-reload* only, from `exeris.config.dir` / `EXERIS_CONFIG_DIR` / `/etc/exeris/config`.
> - **Community's `watch()` is a documented no-op** — hot-reload is an Enterprise capability
>   (`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/config/CommunityConfigProvider.java:151-159`: *"No-op — Community tier does not support hot-reload"*).
>
> So in Community, configuration is entirely system properties and environment variables. See
> [`docs/subsystems/config.md`](../subsystems/config.md) for the full contract.

### HTTP keys

`http.mode`, `http.bindHost`, `http.port` (falls back to `network.port`), `http.maxConnections`,
`http.idleTimeoutMillis`, `http.maxRequestHeaderCount`, `http.maxRequestHeaderSize`,
`http.maxRequestBodyBytes`, `http.maxResponseBodyBytes`, `http.h2cUpgradeEnabled`,
`http.maxVersion`, `http.client.defaultAuthority`, `http.maxHeaderBlockSize`,
`http.maxHeaderListSize`, `http.maxStringLiteralSize`.

The two body limits are **separate keys because they bound opposite directions on different
sockets**: `http.maxRequestBodyBytes` is what this server accepts from callers,
`http.maxResponseBodyBytes` is what this application's HTTP client will read back from someone
else's server. Until 0.12 the client borrowed the request key, so tightening ingress also shrank
what the outbound client could read, and loosening it grew the buffer every response allocates.
Both default to 10 MiB and `-1` means unlimited.

The last three are HTTP/2 only, and they are three keys because they bound three different
quantities — the COMPRESSED header block on the wire, the CUMULATIVE DECODED field section, and a
SINGLE decoded literal. Compression is what makes the first two independent, and the middle one is
what the server advertises as SETTINGS_MAX_HEADER_LIST_SIZE. All three are protective bounds, so
`0` is refused rather than read as "unlimited".

Source: `CommunityHttpConfigResolver.resolve`.

> ### Gotcha: HTTP binds nothing, silently
>
> If **neither** `http.mode` **nor** a port is set, the resolver returns `HttpMode.DISABLED` and the
> subsystem binds no socket. No exception, no warning — the `ScopedValue` slots simply stay unbound.
>
> Source: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/CommunityHttpConfigResolver.java:110-112` (quoted).
>
> ```java
> boolean hasExplicitPort = configProvider.getInt("http.port").isPresent()
>     || configProvider.getInt("network.port").isPresent();
> return hasExplicitPort ? HttpMode.SERVER : HttpMode.DISABLED;
> ```
>
> Set them explicitly:
> `-Dexeris.http.mode=SERVER -Dexeris.http.bindHost=127.0.0.1 -Dexeris.http.port=8080`.

---

## Testing against a real kernel

Do not write a double. `exeris-kernel-community-testkit` ships fixtures that boot the **real** kernel,
and they live in that module's *main* sources so your application can depend on them at `test` scope
(coordinates in [01](./01-platform-and-dependencies.md#test-scope-coordinates)).

Source: `docs/modules/06-testkit.md` (quoted).

```java
try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
    fixture.start(exchange -> exchange.respond(HttpStatus.OK));
    int port = fixture.boundPort();
    // drive a real client at 127.0.0.1:port
}
```

The fixture reserves a loopback port, boots the `http` subsystem, and binds your handler using
exactly the `ScopedValue` pattern shown above. `close()` is a hard stop that releases the boot and
joins.

Because it boots the real thing, a router passed to `fixture.start(...)` behaves as it will in
production — path templates resolve, and a `POST` with a JSON body decodes. That is what
`GeneratedAppBootPathReachabilityIntegrationTest` asserts over a real socket.

There is also a persistence fixture (`EmbeddedPersistenceEngineFixtures.inMemoryH2()`). See
[`docs/modules/06-testkit.md`](../modules/06-testkit.md) for both, the threading rules, and what the
fixtures deliberately do not cover.

---

## When it doesn't work

| Symptom | Cause |
|:--|:--|
| Process exits immediately after boot | Your `Runnable` returned. Park inside it. |
| Nothing listening on the port | `http.mode` and port both unset → `DISABLED`. See the gotcha above. |
| Only `/health*` responds; everything else unserved | `HTTP_SERVER_HANDLER` was never bound — or was bound *inside* `boot()` instead of around it. |
| `BootstrapException` … `[EX-CFG-0001]` | No `ConfigProvider` on the classpath. Add `exeris-kernel-community` — see [01](./01-platform-and-dependencies.md). |
| Ctrl-C leaves resources open | No signal handling exists. Register your own hook to release the park. |
| A subsystem you expected is missing | Check your `BootstrapSelector`. `forNames` pulls transitive dependencies, but not unrelated subsystems. |

---

## Not available today

Stated so you do not go looking:

- **No signal handling** (`docs/subsystems/bootstrap.md`) — you own the shutdown hook.
- **No startup configuration file** in Community; properties and environment variables only.
- **No hot-reload** in Community — `watch()` is a no-op.
- **No example application or Maven archetype** in this repository. The closest runnable references
  are `exeris-kernel-diagnostics-cli` and the integration tests cited above.
- **No published release.** See *Resolving today* in [01](./01-platform-and-dependencies.md).

---

## See also

- [01 — Platform and Dependencies](./01-platform-and-dependencies.md)
- [03 — Implement a Provider](./03-implement-a-provider.md)
- [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md) — boot DAG, state machine, health probes
- [`docs/subsystems/http.md`](../subsystems/http.md) — codec, HTTP/2, operational endpoints
- [`docs/subsystems/config.md`](../subsystems/config.md) — full configuration contract
- [`docs/modules/06-testkit.md`](../modules/06-testkit.md) — the fixtures in full
- [`docs/stability-matrix.md`](../stability-matrix.md) — how far you can lean on each surface
