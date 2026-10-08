---
title: "ADR-103: Native-image opt-in build"
type: adr
visibility: public
owning-repo: exeris-kernel
status: draft
slug: adr/ADR-103
---

# ADR-103: Native-image opt-in build

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **PROPOSED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-10-07                                                                                |
| **Scope**       | `kernel/runtime` — `exeris-kernel-spi`, `exeris-kernel-core`, `exeris-kernel-community`, `exeris-kernel-community-kafka`, `exeris-kernel-build-config` (the `@RequiresRole` processor) |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | v0.13 scope; the ROADMAP stance "Native-image / GraalVM" under *Scope Discipline & Declared Stances*, whose enablement half this ADR moves from post-1.0 to 0.13 |
| **Compliance**  | [docs/performance-contract.md](../performance-contract.md) §2.2.1, [docs/support-matrix.md](../support-matrix.md) "Runtime image" |

## Context and Problem Statement

Three published documents state that GraalVM native-image enablement is post-1.0: the
support-matrix row "Runtime image", `performance-contract.md` §2.2.1, and the ROADMAP stance
"Native-image / GraalVM". The same documents pin the zero-allocation and per-core throughput SLOs to
HotSpot/C2 and call native-image a *different, not broken* contract — the edge/lightweight tier.
That pin is not in question here; the timing of enablement is.

**Nothing in the tree ships native-image configuration today.** No module carries a
`META-INF/native-image/` directory, and no POM or source file references `org.graalvm`. The GA line
compiles with `maven.compiler.release` 25 and needs no `--enable-preview` (ADR-066), so a GraalVM 25
build needs no preview flag either. What an image needs from the kernel, enumerated from the
declaration sites on `development/0.13.0`:

- **FFM downcalls and one upcall.** `downcallHandle` is called in `CoreOpenSslLoader`,
  `CoreOpenSslRuntime`, `CoreSyscallLoader` and `SyscallErrno`; `CommunityAlpnSelector` creates the
  ALPN select-callback `upcallStub`. Native-image registers a downcall only from metadata, and an
  unregistered descriptor fails at run time, not at build time.
- **Reflection by name.** `GeneratedRoleRegistryLoader` resolves
  `eu.exeris.kernel.security.generated.RoleCheckRegistry` with `Class.forName` and binds its static
  accessors; the class is generated in the consumer's build by `RequiresRoleProcessor`
  (`exeris-kernel-build-config`), which today emits only a source file. `JfrEventWarmup` and
  `JfrEventCatalogue` load event classes by name. `SocketChannelFdReflectionResolver` reads JDK
  internals (`getDeclaredMethod` / `getDeclaredField` on the `sun.nio.ch` channel type and
  `java.io.FileDescriptor.fd`).
- **`findVarHandle` targets.** 28 call sites across Core and Community `src/main` (grep count).
- **Resources the kernel reads.** `KernelVersion`, `ManifestLocator` (`license-manifest.json`) and
  `CommunityPersistenceMigrationRunner` (`db/migration/*.sql`) read classpath resources, which an
  image does not include unless named.
- **`ServiceLoader` descriptors.** 16 files under `META-INF/services/` in Community and 1 in
  Community Kafka.
- **Shared arenas.** `Arena.ofShared()` is created in `CommunityArenaShardPool` (one per shard) and
  in `NativeTcpSocketBackend` (one per carrier). `NativeCipherContext` mentions `Arena.ofShared()` in
  its Javadoc only and creates no arena.

**A prototype image was built and run before this ADR**, on Oracle GraalVM 25.0.2, serial GC,
x86-64 Linux, from the `development/0.13.0` reactor jars plus a small driver application. What it
established, each under those conditions:

- **With no metadata the image builds and does not work.** `ServiceLoader` discovery of all 12
  Community subsystems works unaided; the first syscall downcall fails with
  `MissingForeignRegistrationError`, the persistence pool fails to start because the migration and
  manifest resources are absent, and the JFR warm-up loses its by-name classes (logged, boot
  continues).
- **`Arena.ofShared()` requires `-H:+SharedArenaSupport`.** Without it, closing
  `NativeTcpSocketBackend`'s arena throws `UnsupportedFeatureError`, so every kernel shutdown fails.
  The flag is experimental in GraalVM 25.0.2: the build announces that it will require
  `-H:+UnlockExperimentalVMOptions` in a future release.
- **The tracing agent's output is the trace of one run, not a contract.** Metadata recorded from one
  `http,persistence` JVM run made that subsystem set pass and left `crypto` failing on an OpenSSL
  downcall the run never touched, and the `security`, `events`, `flow` and `scheduling` boots each
  losing JFR event classes in the warm-up.
- **TLS works on the same terms as on HotSpot.** On the JVM the FFM socket path and TLS need
  `--add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED` (Community's
  own Surefire `argLine` carries both); without them `SocketChannelFdReflectionResolver` falls back and
  the handshake fails. With the two opens passed as image build arguments and the downcalls
  registered, the kernel's HTTP client over TLS, the JDK `HttpClient` over HTTP/1.1 and over HTTP/2
  (ALPN), and a JFR `RecordingStream` all passed. OpenSSL stays dynamically loaded from the host.
- **The role registry silently empties.** With a generated `RoleCheckRegistry` on the classpath and
  no metadata for it, the image loads `GeneratedRoleRegistryLoader.empty()`: fail-closed, so every
  `@RequiresRole` endpoint denies, and the only signal is the `RoleRegistryLoaded` JFR event.
- **Graph works on both backends with no graph-specific metadata.** Edge upserts, a 2-hop
  traversal and `findShortestPath` passed on PostgreSQL 16 and on Neo4j 5 (Bolt, unencrypted). The
  Neo4j driver, netty and reactor ship their own native-image metadata; the PostgreSQL JDBC driver
  needs only its `java.sql.Driver` service resource.
- **Kafka works once its module names a small, closed set of client types.** `kafka-clients`
  resolves its default assignors, serializers and OAuth defaults by class name from configuration,
  and ships no native-image metadata; without entries for them boot fails with
  `ExceptionInInitializerError`. The set is closed because `KafkaEventProvider` reads a fixed list of
  `events.kafka.*` keys and configures no compression. With the entries, a publish travelled through
  a real broker to a subscriber; with the broker stopped the same image timed out, so the green run
  did not dispatch locally.
- **An `Error` escapes `KernelBootstrap` raw** — the downcall, shared-arena and Kafka failures above
  each reached `main` as the bare `Error`, skipping the failure policy, the `FAILED` health state and
  the boot JFR event. The same reproduces on HotSpot and is filed as #619; it is a kernel defect, not
  a native-image one.
- **Startup and footprint**, `http,persistence`: the prototype image reached `kernelMain` from
  `KernelBootstrap` faster, and ran with a smaller resident set, than the same jars on HotSpot/C2. The prototype
  is one machine and is not a published measurement; the figures this ADR may state are the ones
  the `native-smoke` job records (obligations 10 and 11), as edge-tier measurements, not SLOs.
  Building an image is costly in time and memory compared with any other check.
- **Not measured:** the testkit under native-image, Neo4j over encrypted Bolt, Kafka compression
  codecs, aarch64, Windows.

No failure found is a blocker: each has a fix that can ship inside the kernel's own jars. The
question this ADR answers: **does the kernel support native-image in 0.13, and if so, in what form,
for which artifacts, and under which obligations?**

## Options Considered

### Option 1 — Keep enablement post-1.0

The documents stay as they are. Costs nothing now; leaves the edge tier — the half of the
performance contract where native-image wins — undemonstrable through 1.0, and every consumer who
tries an image meets the failures above with no supported fix.

### Option 2 — Opt-in build, metadata shipped in the kernel's jars *(chosen)*

The kernel artifacts carry their reachability metadata and image build arguments. The JVM ignores
both, so HotSpot users pay nothing; an application that wants an image builds one with
`native-image` or `native-maven-plugin` and adds no configuration of its own for the kernel. HotSpot
remains the default and the only runtime with asserted SLOs.

### Option 3 — Native-image as a first-class runtime with its own SLOs

Same metadata, plus asserted native-image targets and a native build in the default PR gate. The
prototype supplies one machine's observations, not a contract; an image build on every PR is a
cost in time and memory the default gate does not carry for any other check; and the
§2.2.1 reasoning (AOT without profiles makes scalarization and FFM decisions more conservative)
means a native SLO would be a second contract to defend. Rejected for 0.13.

### Option 4 — Document the tracing agent; the consumer owns the metadata

Rejected on measurement: the agent's output covers the paths one run took, so it misses the OpenSSL
downcalls and JFR event classes the run did not reach, and every consumer would repeat that
collection and its gaps.

## 🏁 The Decision

**Native-image is supported from 0.13 as an opt-in build: the kernel artifacts in scope ship their
own reachability metadata and image build arguments, HotSpot/C2 stays the default and the only
runtime whose performance targets are asserted, and native-image startup and footprint are reported
as edge-tier measurements.**

This is planned for 0.13. The 0.12 artifacts ship no metadata, and an image built from them is not
supported.

**Concrete obligations:**

1. **Opt-in, HotSpot default.** No kernel build, test or release step requires GraalVM. The
   zero-allocation and per-core throughput SLOs remain pinned to HotSpot/C2 exactly as
   `performance-contract.md` §2.2.1 states; this ADR changes when native-image is supported, not
   which contract it is held to.

2. **Artifact scope.** In scope: `exeris-kernel-spi`, `exeris-kernel-core`,
   `exeris-kernel-community` — including the graph subsystem on both backends, PostgreSQL (default)
   and Neo4j — and `exeris-kernel-community-kafka`. `exeris-kernel-community-testkit` is out of scope
   until an image run of it is measured. The TCK, `exeris-kernel-diagnostics-cli` and the BOM are not
   runtime artifacts of an application image and carry no metadata. Configurations the prototype did
   not exercise — Neo4j over encrypted Bolt, Kafka compression codecs, aarch64, Windows — are stated
   as unverified in the support matrix, not as supported.

3. **Metadata location and format.** Each in-scope module ships
   `META-INF/native-image/eu.exeris/<artifactId>/reachability-metadata.json` (the single-file
   reachability-metadata format) and, where it needs build arguments,
   `META-INF/native-image/eu.exeris/<artifactId>/native-image.properties`. Community's
   `native-image.properties` carries `--enable-monitoring=jfr`, the two `--add-opens` of obligation
   6, and the shared-arena argument only as obligation 8 rules.

4. **Metadata comes from declaration sites, not from the tracing agent.** Each module's metadata is
   generated from, or checked against, its declaration sites: every `downcallHandle` and
   `upcallStub` descriptor, every class in the JFR event catalogues, every `findVarHandle` target,
   every classpath resource the module reads, and every class the module or its dependency resolves
   by name on a path the module configures (for Kafka: the client types its fixed key set selects).
   Each module carries a unit test that fails when one of those sites has no matching metadata entry.
   Agent output may be used to find a candidate entry; it is never the source of truth.

5. **The processor emits metadata for the class it generates.** `RequiresRoleProcessor` writes a
   `reachability-metadata.json` next to the `RoleCheckRegistry` it generates, registering the type
   and the static accessors `GeneratedRoleRegistryLoader` binds. Where no class is generated, the
   loader still returns the fail-closed empty registry, in the image as on the JVM. A processor test
   asserts the emitted file.

6. **File-descriptor access: register the resolver, ship the opens.** `NativeTcpCarrier`,
   `NativeTcpStream`, `NativeTcpStreamTlsHandshake` and `CommunityTlsEngine` obtain the socket's fd
   through `SocketChannelFdAccess.requireFd`, which is reflection on JDK internals. The JDK members
   `SocketChannelFdReflectionResolver` reaches are registered in Community's metadata, and the two
   `--add-opens` the JVM path already needs ship in Community's `native-image.properties`, so the
   consumer passes nothing to the image build. On the JVM the opens remain the consumer's flag, as
   today.
   - *Option considered and rejected:* detect the image at run time and route every carrier through
     `bindFileDescriptor(int)`. The carrier has no `int` fd except through `requireFd`, so the
     detection would reach the same reflection; it would also put an image check into Community's
     hot setup path for no gain.

7. **Minimum GraalVM 25.** The artifacts are compiled for release 25, which an older native-image
   cannot read, and 25.0.2 is the version measured. The support matrix states GraalVM 25 or newer,
   and OpenSSL 3 on the host for the TLS paths, as on the JVM.

8. **Shared arenas.**
   - **`NativeTcpSocketBackend` moves off `Arena.ofShared()`** (recommended). Its arena backs no
     allocation on the path that creates it: on POSIX, `CoreSyscallLoader.load` resolves symbols
     through `Linker.defaultLookup()` and allocates nothing in the arena, which its Javadoc says is
     held for API symmetry; on Windows, `SocketBackendSelection.resolve` returns before calling
     `load`, so the `WSADATA` scratch buffer `loadWindows` would allocate in the arena is never
     requested from this class. The recommendation is to pass `Arena.global()` — what
     `CommunityKernelCryptoProvider` already passes to `CoreOpenSslLoader.load`, on a path measured
     green in the image — and stop closing it, since a global arena cannot be closed. `Arena.ofAuto()`
     is the alternative if a Windows FFM socket path is ever armed through this class, so a
     per-carrier `WSADATA` buffer is reclaimed rather than retained; it is not measured in an image.
   - **`CommunityArenaShardPool` keeps `Arena.ofShared()`.** Its segments travel inside
     `LoanedBuffer`s across threads, and `close()` frees every shard deterministically; a confined
     arena cannot be used across threads and neither a global nor an automatic arena can be closed.
   - **`-H:+SharedArenaSupport` ships in Community's `native-image.properties` only if the pool still
     needs it** after the socket backend moves, which an image shutdown after pool use shows.
     **Ruling required:** whether the kernel may ship an experimental GraalVM option inside its jars.
     The alternative is to leave the pool's arena kind to a later change and document the flag as the
     consumer's own build argument, at the cost of "no configuration of its own".
   - `NativeCipherContext` creates no arena and needs no change.

9. **No GraalVM type in the kernel.** No `org.graalvm.nativeimage.*` type appears in SPI, and no
   module takes a compile dependency on any GraalVM artifact. Native-image support is metadata and
   build arguments only; no kernel code branches on whether it runs in an image.

10. **A native CI job outside the default PR gate.** A `native` Maven profile, inactive by default,
    builds an image of a sample application from the reactor jars with no application-side
    configuration. A `native-smoke` job runs it on a label, nightly and on the release pull request,
    not on every PR. It asserts at run time the boot of every Community subsystem that needs no
    external server, HTTP/1.1 and HTTP/2 over TLS, persistence, a custom JFR event read back and a
    `RecordingStream` delivery, a non-empty generated role registry, graph on PostgreSQL and Neo4j
    containers, and a Kafka round trip through a broker container. The job is made red first: with
    one module's metadata file deleted it fails on that module's path, and a skipped assertion counts
    as a failure.

11. **Edge-tier measurements, not SLOs.** Image startup time and RSS are published in release notes
    as measurements with their toolchain, GC, hardware and sample size. No document states them as
    targets.

12. **#619 is a prerequisite.** Every native-image failure mode measured so far is an `Error`; until
    `KernelBootstrap` routes an `Error` through the failure policy, an image with a metadata gap
    fails outside the health and JFR reporting the kernel promises. The metadata work lands after
    the #619 fix.

## Consequences

### ✅ Positive Outcomes

- **[+] The edge tier becomes demonstrable before 1.0.** The half of the performance contract where
  native-image wins can be shown with the kernel's own artifacts, not argued.
- **[+] JVM users pay nothing.** Metadata and `native-image.properties` are inert on HotSpot, and no
  default gate grows.
- **[+] Metadata is held to the code.** A per-module test turns "a new downcall without metadata" into
  a build failure instead of a run-time `Error` in somebody's image.
- **[+] One shared arena fewer.** The socket backend's arena, which backs no allocation, stops being
  a shared arena on every carrier.

### ⚠️ Trade-offs

- **[-] Third-party metadata becomes a dependency.** Neo4j support rests on the driver's, netty's and
  reactor's own metadata; a driver upgrade can change it. The smoke job pins and exercises it, but
  the kernel does not own it.
- **[-] Kafka metadata names client classes.** The Kafka module's entries track `kafka-clients`
  internals selected by name; a client upgrade can add one, and only the smoke job notices.
- **[-] CI cost.** An image build needs far more time and memory than any other check; the job
  needs a large runner or a long budget, which is why it is outside the default gate.
- **[-] An experimental flag may ship.** If obligation 8 ends with the pool still on a shared arena
  and the ruling allows it, Community's jar carries an option GraalVM has announced it will gate.

### 📋 What is NOT in scope

- Native-image performance targets of any kind.
- A native-image build of the testkit, the TCK or the diagnostics CLI.
- Metadata for the consumer's own types: the kernel registers what it reads by name, not what an
  application binds through Jackson or loads itself.
- The #619 fix itself, which is its own change.

### 🚫 Non-Goals

- Making native-image the default runtime or a merge gate on every PR.
- Profile-guided optimisation as part of the supported build.
- Static linking of OpenSSL into the image; it stays a host library, as on the JVM.

### ⚠️ Risks and Assumptions

- **Assumes:** native-image keeps reading `META-INF/native-image/<group>/<artifact>/` from the
  classpath and the single-file `reachability-metadata.json` format; GraalVM 25 remains able to build
  release-25 class files that use FFM without preview.
- **Reversed by:** a measured failure in the native-smoke job that no metadata or build argument can
  fix, or a GraalVM release that removes shared-arena support without a replacement while
  `CommunityArenaShardPool` still needs it.
- **Risk:** a metadata gap reaches a release on a path the smoke job does not exercise. The
  declaration-site test (obligation 4) narrows that to sites the enumeration does not know about —
  the same class of gap as a resource read by a new mechanism.

## Cross-references

- [ADR-006](ADR-006.link.md) (Spring-Free Kernel Boundary) — obligation 9 keeps a GraalVM type out of SPI on the same
  grounds a Spring type is kept out.
- [ADR-008](ADR-008-open-core-strategy-and-commoditization-of-off-heap-tls.md) — the off-heap OpenSSL TLS binding whose downcalls the Community
  metadata registers.
- [ADR-014](ADR-014-requiresrole-compile-time-rbac-generation.md) — the `@RequiresRole` processor obligation 5 extends.
- [ADR-066](ADR-066-preview-clean-ga-baseline.md) — the preview-clean baseline that lets a GraalVM 25
  image build without `--enable-preview`.
- [`docs/performance-contract.md`](../performance-contract.md) §2.2.1 — the HotSpot/C2 pin this ADR
  leaves unchanged.
- [`docs/support-matrix.md`](../support-matrix.md) — row "Runtime image".
- [`docs/ROADMAP.md`](../ROADMAP.md) — *Native-Image: Opt-In Build With Metadata In The Jars
  (ADR-103)*, and the "Native-image / GraalVM" stance under *Scope Discipline & Declared Stances*.
- #619 — an `Error` from a subsystem bypasses the failure policy and escapes `KernelBootstrap`.

## Engineering Protocol

1. A per-module unit test enumerates the module's declaration sites (obligation 4) and fails on a
   missing metadata entry; it ships with the metadata, not after it.
2. `RequiresRoleProcessorTest` asserts the emitted `reachability-metadata.json` (obligation 5).
3. An ArchUnit or dependency check rejects any `org.graalvm` import or dependency (obligation 9).
4. The `native-smoke` job (obligation 10), made red first by deleting one module's metadata file.
5. `docs/support-matrix.md` row "Runtime image" changes from "planned for 0.13" to "supported as an
   opt-in build" only in the change that lands the metadata and a green `native-smoke` run.
