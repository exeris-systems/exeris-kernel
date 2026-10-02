---
title: "Migration guide — Exeris Kernel (open-core)"
type: migration-guide
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-30
---

# Migration guide — Exeris Kernel (open-core)

The upgrade steps between kernel versions: what a consumer edits, sets or recompiles. What changed
and why is in [`CHANGELOG.md`](CHANGELOG.md) and the per-release notes under `docs/release/`.

This guide covers only changes that ask something of a consumer. A behaviour change that needs no
edit, a new feature, and the reasoning behind a change are in the release notes, so reading an
upgrade in full takes both documents: the steps here, the context there.

> **Versioning policy.** The kernel is `0.x` until 1.0.0 GA. Which SPI surfaces are `stable`,
> `preview` or `experimental`, and what each label commits to, is declared in
> [`docs/stability-matrix.md`](docs/stability-matrix.md). Core, Community and the Maven coordinates
> are not declared surfaces, so a minor release can change them; each such change is listed under
> the changelog's `### Breaking` and has a step here.

---

## 0.11.x → 0.12.x

Where each `### Breaking` entry of [`CHANGELOG.md`](CHANGELOG.md#0120--2026-09-30) is handled:

| Changelog `### Breaking` entry | Step |
|:--|:--|
| core: `HttpRouter.StreamMatch` is the SPI record `StreamMatch`; `resolveStream` returns it | 7 |
| community: `CommunityOidcIdentityProvider.overJwksEndpoint` takes the JWKS URI; the provider is `AutoCloseable` | 8 |
| tck: the `tests` classifier / `test-jar` coordinate is gone, from `exeris-kernel-bom` too | 4 |
| http: the HTTP client no longer dials `http.bindHost:http.port` | 3 |
| http, community: the Community TLS client verifies its server | 8 |
| persistence: `RowCursor.getString` throws `EX-PERS-5008` outside the measured type set | 12 |
| events: `EX-EVENT-6002` matches only queue overflow | 10 |
| tck: `AbstractSharedScopeAccessMatrixTck` declares three more store operations | 4 |
| core: `HttpRouter.Builder.streamRoute` refuses a repeated stream route | 15 |

Step 13 is the licence change, which needs no code edit. Step 14 is for a deployment that copied the
kernel's reference shared-scope RLS policy; it is a database change, not a code edit.

1. **Re-read your transport and HTTP configuration.** Nothing changed shape, but limits the runtime
   ignored, compiled in or borrowed are now enforced as configured — see "[knobs that did nothing](docs/release/v0.12.0-release-notes.md#compatibility-knobs-that-did-nothing-now-do-something)"
   in the release notes — and the HTTP connection cap
   defaults to 4 096, so check `ulimit -n` against it. For a deployment that makes no outbound HTTP
   calls, this is the section most likely to change what it does; for one that does, step 3 is at
   least as likely to.
2. **Nothing to recompile at the SPI.** No SPI signature moved; code that uses only the SPI needs no
   recompilation. Step 7 is a Core change that does, and step 4 lists a testkit interface that
   gained a method. Two runtime defaults did move — the HTTP client's destination, step 3, and TLS
   server verification, step 8.
3. **If the deployment makes outbound HTTP calls, name the peer.** `http.bindHost` and `http.port`
   are listen addresses only, and the HTTP client no longer reads them. At v0.11.0 a `CLIENT`
   client dialled `http.bindHost:http.port`, so a deployment reached its peer through those keys
   when it pointed them at a remote peer, or when it left them unset and their defaults — `0.0.0.0`
   for the host, and `network.port`, else `8080`, for the port — reached a listener on the same host.
   A booted `DUAL` kernel did not start at v0.11.0, so a `DUAL` deployment has no earlier peer to
   carry over; it names one the same way. Each of those now needs one of:
   - `http.client.defaultAuthority=host:port` — the port is required, an IPv6 address is bracketed
     (`[::1]:8443`), and a value carrying a scheme or a path is refused at startup; or
   - an address on each request: `KernelWebClient.withAuthority(String)` or
     `HttpRequest.withAuthority(String)`.

   A peer reached over TLS is verified against the host named here, so its certificate must carry
   that host in a subject alternative name — a DNS entry for a name, an IP entry for an address
   (step 8).

   Code that builds `HttpConfig` itself through the ten-argument constructor gets no default peer,
   and `HttpConfig` has no `with…` method for one: move to a constructor that takes
   `defaultAuthority` — the fourteen-argument one, or the canonical constructor. **If this step is
   missed**, the kernel still boots cleanly and with no warning; the first request that names no
   peer throws `IllegalStateException` naming `http.client.defaultAuthority`.
4. **If you consume `exeris-kernel-tck`**, drop the classifier — the contract is the main artifact.
   Peer addressing (ADR-074) also adds cases a binding that passed the 0.11 suite can fail:
   - `AbstractHttpClientEngineTck`: a started engine's `defaultAuthority()` reports the configured
     `HttpConfig#defaultAuthority()`, and `null` when none is configured; an engine that wraps
     another must report it too. The engine refuses with `IllegalStateException` a request naming
     no authority when it has no default, and a request whose authority carries no port.
   - `AbstractHttpProviderLoopbackTck`: a request naming an authority reaches that peer rather than
     the configured default. A request carrying no `Host` of its own reaches the server with exactly
     one, equal to its effective authority: the authority it names, or the configured default when
     it names none. The `Host` cases address the server through the new hook `loopbackHostName()`
     (default `localhost`), which must resolve to `loopbackHost()`. The `clientConfig` fixture names
     the peer only as its `defaultAuthority`, with no `bindHost` and the `-1` port, so a client that
     dials `bindHost` and `port` fails every unaddressed case; an override of `clientConfig` must
     name the peer the same way.
   - `AbstractTransportConnectionTck`: `remoteAddress()` is an IP address literal on both ends,
     including a client end dialled by host name — the contract the
     `TransportConnection#remoteAddress()` Javadoc now states, with no signature change. Where the
     transport dials by name, `createConnectionPair` should open the client end that way; against an
     address literal the case cannot tell a name from an address. The Community transport now meets
     it on the dialled end — see "A Community client connection reports the address it reached"
     under [*Compatibility: observable behaviour changes*](docs/release/v0.12.0-release-notes.md#compatibility-observable-behaviour-changes)
     in the release notes.

   Later contract corrections add cases a binding that passed the 0.11 suite can fail, with no hook
   or signature changed unless stated:
   - `AbstractHttpClientEngineTck`: `send(null)` throws `NullPointerException` on an engine not yet
     started, and an engine that closes or retains the request body fails (ADR-034 Amendment A1).
   - `AbstractEventBusTck`: on a bus whose `isBrokered()` is `true` its five in-process cases are
     skipped through an assumption; every bus runs a new case in which the payload's `close()`
     calls equal one plus its `retain()` calls once `publishAndAwait` returns.
   - `AbstractWebSocketExchangeTck.Close#receiveEndsOnClose` waits for the handler's `receive()` to
     return `null`, so a binding whose `receive()` stays blocked after the peer's close frame fails.
   - `TransportCarrierPinningTck` fails in setup a binding whose `createWritableStream()` returns a
     stream an earlier slot already holds.
   - `JfrPinningMonitor.Result`: `pinnedEvents()`, `pinnedCount()` and `hasPinning()` answer only
     for counted pins; class-loading and class-initialisation pins move to `classInitEvents()`. The
     earlier meaning is the two together. The records keep their constructors, so a binding still
     compiles and links; what changed is what the answer means.
   - `AbstractCryptoEngineTck`: the three Community-only checks are skipped, not passed, for a
     binding whose `isCommunityTier()` is `false`.
   - `AbstractSharedScopeAccessMatrixTck` declares three more abstract store operations, so a
     binding does not compile until it adds them: `updateValue(ctx, value, newValue)`,
     `reassignOwner(ctx, value, newOwner)` and `delete(ctx, value)`, each returning the affected row
     count or throwing a `PersistenceProviderException` when the store refuses. Its new cells fail a
     binding whose policy lets a tenant update, re-own or delete a partition-mate's row, and one
     whose owner cannot update and delete its own. A binding whose owner or scope column is not text
     overrides `ownerA()`, `ownerB()` and `sharedScope()`.

   **If you implement a testkit fixture interface**, `EmbeddedHttpEngineFixture` gained
   `runInKernelScope(Runnable)`, which a class outside the kernel adds.
5. **If you are on the module path**, module names are now declared rather than derived.
6. **WebSocket is opt-in.** Upgrading opens no socket; set `websocket.enabled` to change that, and
   set `websocket.allowedOrigins` before a browser can connect.
7. **If your code names `HttpRouter.StreamMatch` or calls `HttpRouter#resolveStream`, recompile it.**
   The record is now `eu.exeris.kernel.spi.http.StreamMatch` (SPI, preview), with the same
   components and the same `exact(HttpStreamHandler)` factory, and it refuses a `null` handler or
   parameter map at construction; import it from there in place of `HttpRouter.StreamMatch`. What
   changes is code naming the Core router's nested record or calling its method; the SPI side of the
   move is an addition, so step 2 holds. A handler you bind to
   `HTTP_SERVER_HANDLER` that wraps an `HttpRouter` and should serve its stream routes implements
   `StreamRouteResolver` by delegating to the router; one that does not is served respond-once, as
   before.
8. **TLS clients verify their server.**
   - A client that talks to a private-CA or self-signed server needs `crypto.tls.client.trustFile`
     naming the issuing CA — with its intermediates when the server's certificate is chained, since
     the Community server sends its leaf only — or, for a self-signed server, that certificate. A
     CA-issued server certificate alone in the file does not anchor its chain. The file replaces
     OpenSSL's default trust; `SSL_CERT_FILE` and `SSL_CERT_DIR` change only the default.
   - A client that talks to a plaintext peer declines TLS with `-Dexeris.transport.tls=false`. It is
     process-wide: every listener and client in the JVM declines together.
   - A deployment whose bound crypto provider is not the Community one cannot build a Community
     client transport that dials TLS. A `CLIENT` transport — the one a Community HTTP client engine
     builds, the S3 blob client's for an `https://` endpoint — fails at construction with
     `EX-NET-4004`, and a `DUAL` transport whose listener holds certificate material refuses each
     `connect` with `EX-NET-4001`, caused by `TlsHandshakeException` detail
     `bound crypto provider cannot verify an outbound peer`. A client that must dial TLS is built
     where the Community crypto provider is bound; a client of a plaintext peer declines TLS as
     above.
   - An S3 store with an `https://` endpoint needs the Community crypto provider bound where the
     store is built — in a booted kernel, the `crypto` subsystem in the same boot, since storage does
     not depend on it — and a trust that anchors the store's certificate chain: the store's issuing CA
     in `crypto.tls.client.trustFile`, or a public CA in OpenSSL's default trust. Its certificate
     must name the endpoint host in a subject alternative name — a DNS entry for a name, an IP entry
     for an address. Under `-Dexeris.transport.tls=false` an `https` store is refused with
     `EX-NET-4004`, never downgraded; booted, that refusal fails storage's `start()`, and so the
     boot. An `http://` endpoint needs no crypto provider and no trust, and is plaintext wherever
     the store is built.
   - An OIDC provider built by `overJwksEndpoint` is given the JWKS URI instead of a client and a
     path, and is closed with the application. For an `https` URI it needs the Community crypto
     provider bound where it is built and a trust that anchors the identity provider's chain; under
     `-Dexeris.transport.tls=false` it is refused with `EX-NET-4004`. An identity provider reachable
     only over plaintext is given an `http://` URI.
   - Code that calls the Community crypto provider's
     `createTlsEngine(CryptoProviderConfig.tcpClient())` and handshakes the engine now gets
     `TlsHandshakeException` from `beginHandshake`. Build a handshaking client engine with
     `CommunityKernelCryptoProvider#createClientTlsEngine`, which takes the trust and the expected
     peer.
   - A client of public HTTPS servers needs a non-empty default trust store, or a `trustFile` holding
     the public roots it should trust. An OpenSSL build or image whose default store is empty
     verifies no server; a chain sent without its root fails with `X509_V_*` code 20. The posture
     event reports the default file and directory in use, and the transport logs a WARNING when
     neither exists.
9. **A JFR rule keyed on `EX-NET-2002` for handshake failures now matches none.**
   `eu.exeris.kernel.tls.HandshakeFailure` carries `EX-NET-2001`; key the rule on that code, or on
   `verifyResult` to single out certificate verification.
10. **A filter keyed on `EX-EVENT-6002` now matches only queue overflow.** A publish the bus did not
    accept is `EX-EVENT-6009`, handlers that threw under `publishAndAwait` are `EX-EVENT-6010`, a
    rejected subscription is `EX-EVENT-6011`, and an `EventBusException` built through a message
    constructor carries `EX-EVENT-6001`. Code that relied on `publishAndAwait` to await handlers on
    the Kafka bus relied on something it never did: that bus awaits the broker, and
    `EventBus.isBrokered()` says so.
11. **If your code calls `HttpClientEngine#send` directly**, release the request body after `send`
    returns or throws; the engine does not. `KernelWebClient` does this for its own calls. An
    engine you implement reads the body during `send` and neither closes nor retains it.

12. **If your code reads PostgreSQL columns with `RowCursor.getString`**, check the column types
    against [`docs/rowcursor-type-set.md`](docs/rowcursor-type-set.md). Over a type in that measured
    set it returns the server's own rendering; over a type outside it, it throws `EX-PERS-5008`
    naming the declared type, where it returned a value before (ADR-080). Read such a column with
    a typed accessor that represents it without loss (ADR-080 §3), or select it as a type in the set.
13. **If you evaluated the licence, re-read it.** Every published POM declares Apache-2.0, and
    `LICENSE` is the unmodified Apache License 2.0; the Commons Clause condition is gone. No code
    edit follows from it. See "[Licence](docs/release/v0.12.0-release-notes.md#licence-the-kernel-is-now-apache-license-20-unmodified)"
    in the release notes.
14. **If your database carries the shared-scope RLS policy from `RlsConnectionInterceptor`'s
    Javadoc, replace it.** That single policy's widened `USING` also governs `UPDATE` and `DELETE`, so
    a tenant can delete a partition-mate's shared row and re-own one. Drop it and create the two
    policies the Javadoc now gives: the tenant-private policy for every command, and an additive
    `FOR SELECT` policy that widens reads on `exeris.shared_scope`. Reads are unchanged; writes to a
    partition-mate's row now affect no row.
15. **If your code registers the same stream route twice, keep one.** `HttpRouter.Builder.streamRoute`
    refuses a stream route whose method and path are already registered, with
    `IllegalArgumentException` at the call. Keep the registration that was being served: for an exact
    path the later one, which replaced the earlier; for a repeated template the earlier one, which
    matched first.
