---
title: "Build an Application on Exeris Kernel"
type: tutorial
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Build an Application on Exeris Kernel

**Audience:** you are writing an application and want the kernel to run it — boot it, serve HTTP,
configure it, and test it.

**Prerequisite:** [01 — Platform and Dependencies](./01-platform-and-dependencies.md). This page
assumes `eu.exeris:exeris-kernel-community` is on your classpath.

> **Verified against** `0.12.0` at commit `38208e69`, 2026-09-08, on the `development/0.12.0` line.
> Every snippet below is quoted or minimally adapted from the cited class or method, not a line
> range — line numbers rot on every unrelated edit to the same file, symbols don't. If a snippet
> and its source disagree, **the source wins and this guide is the bug**.

---

## What you are building

The kernel is a library that runs inside *your* JVM process, started from *your* `main()`. There is
no container to deploy into, no application server, and no framework that owns the lifecycle. You
call `KernelBootstrap`, it brings up the subsystems you asked for, and it hands control back to a
`Runnable` you supply.

---

## The one thing to get right

**`boot(Runnable)` is blocking, and the `Runnable` you pass it *is* your application's lifetime.**
When that `Runnable` returns, the kernel shuts down — in reverse-topological order, from the
`finally` block inside `KernelBootstrap`'s private `runBootInsideScope(...)`, which always calls
`orchestrator.shutdown()` regardless of how the boot exited.

So a server must **park inside the lambda**. If you boot and return immediately, you have written a
program that starts a kernel and then stops it.

Source: `KernelBootstrapHttpEngineFixture`'s runtime lambda, in
`exeris-kernel-community-testkit/src/main/java/eu/exeris/kernel/community/testkit/http/`
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

`KernelBootstrap` is a builder. This is the only `main()` in the repository that boots the kernel
(a second `main()` exists in the `jfr-reporter` tool, but it post-processes recorded JFR files
offline and never touches `KernelBootstrap`):

Source: `DiagnosticsCli.main(String[])`, in
`exeris-kernel-diagnostics-cli/src/main/java/eu/exeris/kernel/diagnostics/cli/`
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

Builder options (`KernelBootstrap.Builder`): `selector(...)`, `failurePolicy(...)`,
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

Source: `BootstrapSelector.all()`, `.none()`, `.forNames(String...)`.

The Community subsystems, with what each declares:

| Subsystem | `dependsOn()` | `phase()` |
|:--|:--|:--|
| `memory` | — | FOUNDATION |
| `crypto` | `memory` | SERVICES |
| `security` | `memory` | SERVICES |
| `persistence` | `memory` | SERVICES |
| `storage` | `memory` | SERVICES |
| `transport` | `memory`, `crypto` | SERVICES |
| `graph` | `memory`, `persistence` | SERVICES |
| `scheduling` | — | SERVICES |
| `events` | `memory`, `persistence` | RUNTIME |
| `flow` | `persistence` | RUNTIME |
| `http` | `memory` | RUNTIME |
| `websocket` | `memory` | RUNTIME |

Source: the `name()` / `dependsOn()` / `phase()` methods of the twelve `Community*Subsystem`
classes in `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/bootstrap/`
(read from source, 2026-09-08). `storage` and `websocket` are the two additions since this page was
last checked — do not assume the ten-subsystem set from an older reading is still the whole story.

Phases run in order — `FOUNDATION(0)` → `SERVICES(1)` → `RUNTIME(2)` — and a subsystem in phase N
starts only after every subsystem in phase N-1 is `RUNNING` (`BootstrapPhase`'s class Javadoc,
"The Holy Order").

> **Note on [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md).** Its Diagram 1 predates
> the current Community set — it omits `security`, `scheduling`, `storage`, and `websocket` — and
> its `(parallel)` phase labels predate the v0.11 change described later in that same document,
> where phases start on the booting thread (ADR-066). The table above is the current set; read the
> diagram for the DAG concept, not the membership.

### Failure policy

`FAIL_FAST` is the default: any subsystem failing to start aborts the boot. `DEGRADE` continues past
failures, but only for subsystems that declare `isOptional()`, and never for `FOUNDATION`.

---

## Serving HTTP

### The rule: bind the handler *around* `boot()`, not inside it

Your handler reaches the HTTP engine through a `ScopedValue`,
`HttpKernelProviders.HTTP_SERVER_HANDLER`. The HTTP subsystem reads it during `start()`.

That ordering is the whole trick. From `BootstrapPhase`'s class Javadoc:

> *"the Core orchestrator starts them in order on the booting thread (ADR-066), because a subsystem's
> `start()` must observe the `ScopedValue` bindings the application established around `boot()`, and
> those cannot be carried onto another thread."*

Bind it inside the lambda and the subsystem has already started without it.

Source: `KernelBootstrapHttpEngineFixture`'s runtime lambda, in
`exeris-kernel-community-testkit/src/main/java/eu/exeris/kernel/community/testkit/http/`
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

`HttpHandler` is a `@FunctionalInterface` with one method, `void handle(HttpExchange)`. On the
exchange you get `request()`, `pathParams()`, and `respond(...)` — which you must call
**exactly once**.

Source: `KernelBootstrapHttpEngineFixtureIntegrationTest`, in
`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/testing/http/`
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

`respond` has four forms (`HttpExchange`): `respond(HttpResponse)`,
`respond(HttpTypedResponse)`, `respond(HttpStatus, Object)` — which serialises the payload through
the response encoder — and `respond(HttpStatus)` for a bare status.

### Routing

You do not have to `switch` on paths. `HttpRouter` **is** an `HttpHandler`
(`public final class HttpRouter implements HttpHandler`), so it drops into the same slot.

Source: `GeneratedAppBootPathReachabilityIntegrationTest`, in
`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/testing/http/`
(quoted, two fragments joined: `byIdRoutesResolveAndJsonPostDecodes()` and its
`respondWithCapturedId` helper).

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

Builder surface (`HttpRouter.Builder`): `route(method, path, handler)`,
`route(handler, path, methods...)`, `prefixRoute(method, prefix, handler)`,
`streamRoute(method, path, streamHandler)` for SSE, and `notFound(handler)`.

Resolution precedence is **exact → template → prefix**, with a HEAD→GET fallback.

---

## Configuration

There is no configuration file. Community resolves every key in this order:

1. System property `exeris.<key>` — e.g. `-Dexeris.http.port=8080`
2. Environment variable `EXERIS_<KEY>` — dots and dashes become underscores, uppercased: `EXERIS_HTTP_PORT=8080`
3. The compiled default

Source: `CommunityConfigProvider`'s "Resolution Order" class Javadoc.

Inside your application, read config with `KernelProviders.CURRENT_CONFIG.get()`.

> **Two limits worth knowing before you design around config.**
> - **A file can be read at startup — but only for `@Immutable`-guarded keys.** The
>   `DynamicConfigFileWatcher` in Core parses `.properties` files for hot-reload, and — for every
>   key sealed via `ConfigProvider.guardImmutable(file, key)` with a file registered — it also
>   performs one synchronous startup read to seed the pre-boot baseline that a later on-disk
>   mutation is checked against and refused. This runs once at boot, whether or not the file ever
>   changes. It resolves its watch directory from `exeris.config.dir` / `EXERIS_CONFIG_DIR` /
>   `/etc/exeris/config`.
> - **Community's `watch()` is a documented no-op** — hot-reload is an Enterprise capability
>   (`CommunityConfigProvider.watch(String, String, Consumer)`: *"No-op — Community tier does not
>   support hot-reload"*).
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
what the outbound client could read. Both default to 10 MiB, and `-1` disables the server-side
request limit — only the server-side one.

`http.maxResponseBodyBytes` is a **ceiling, not a reservation**: the client reads into a buffer that
starts at 8 KiB and grows to what the response's `Content-Length` declares, so raising it does not
make a small response cost more. Raise it when you fetch large objects; it bounds the largest single
response the client will assemble, and one past it is refused rather than truncated.

**`-1` is refused on this key**, and it is the one limit with no unlimited setting. The ceiling
bounds how much a remote peer can make your client allocate, so "no limit" asks for the protection
the key exists to provide to be absent — a server may accept unbounded requests because it controls
its callers, a client is exposed to someone else's behaviour. The upper bound is `Integer.MAX_VALUE`
(a response is assembled into one buffer), and a value past it is refused rather than quietly
lowered. Configs built through the pre-0.12 constructor with an unlimited request limit still work:
the server side stays unlimited and the client ceiling takes the 10 MiB default.

The last three are HTTP/2 only, and they are three keys because they bound three different
quantities — the COMPRESSED header block on the wire, the CUMULATIVE DECODED field section, and a
SINGLE decoded literal. Compression is what makes the first two independent, and the middle one is
what the server advertises as SETTINGS_MAX_HEADER_LIST_SIZE. All three are protective bounds, so
`0` is refused rather than read as "unlimited".

Source: `CommunityHttpConfigResolver.buildHttpConfig(ConfigProvider)`.

> ### Gotcha: HTTP binds nothing, silently
>
> If **neither** `http.mode` **nor** a port is set, the resolver returns `HttpMode.DISABLED` and the
> subsystem binds no socket. No exception, no warning — the `ScopedValue` slots simply stay unbound.
>
> Source: `CommunityHttpConfigResolver.resolveMode(ConfigProvider)` (quoted).
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
| A subsystem you expected is missing | Check your `BootstrapSelector`. `forNames` only records the names you gave it — the orchestrator expands those to their transitive `dependsOn()` closure, but never pulls in an unrelated subsystem. |

---

## Not available today

Stated so you do not go looking:

- **No signal handling** (`docs/subsystems/bootstrap.md`) — you own the shutdown hook.
- **No startup configuration file** in Community; properties and environment variables only.
- **No hot-reload** in Community — `watch()` is a no-op.
- **No example application or Maven archetype** in this repository. The closest runnable references
  are `exeris-kernel-diagnostics-cli` and the integration tests cited above.
- **Publish status.** The reactor is at the release version `0.12.0` (not a `-SNAPSHOT`) as of this
  writing, but this page cannot itself confirm whether that version currently resolves from GitHub
  Packages or Maven Central — check *Resolving today* in [01](./01-platform-and-dependencies.md) for
  the current answer rather than trusting a state that predates the release cut.

---

## See also

- [01 — Platform and Dependencies](./01-platform-and-dependencies.md)
- [03 — Implement a Provider](./03-implement-a-provider.md)
- [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md) — boot DAG, state machine, health probes
- [`docs/subsystems/http.md`](../subsystems/http.md) — codec, HTTP/2, operational endpoints
- [`docs/subsystems/config.md`](../subsystems/config.md) — full configuration contract
- [`docs/modules/06-testkit.md`](../modules/06-testkit.md) — the fixtures in full
- [`docs/stability-matrix.md`](../stability-matrix.md) — how far you can lean on each surface
