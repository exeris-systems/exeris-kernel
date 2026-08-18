# Platform and Dependencies

**Audience:** anyone consuming Exeris Kernel as a dependency — whether you are building an
application on it or implementing a provider for it.

> **Verified against** `0.11.0-SNAPSHOT` at commit `1b93bf65`, 2026-08-11. Every snippet below is
> quoted or minimally adapted from the cited file, and the citation is printed above it. If a
> snippet and its source disagree, **the source wins and this guide is the bug**.

---

## Scope

This page answers the *consumer* question: which JDK, which artifact line, which JVM flags, and
which Maven coordinates. It is the only page that states them.

It does **not** cover the toolchain for building this repository from source — that is
[`CONTRIBUTING.md`](../../CONTRIBUTING.md), and it answers a different question with different
requirements.

---

## Which artifact line

v0.11 ships two artifacts. They carry the **same kernel** — same SPI, same subsystems, same tests —
and differ in one axis plus the build that follows from it.

| | `eu.exeris:*:0.11.0` | `eu.exeris.preview:*:0.11.1` |
|:--|:--|:--|
| **JDK** | **25 LTS** | newest available — JDK 28 EA today |
| **`--enable-preview`** | **not required, and not imposed on you** | required, by definition |
| **Structured concurrency** | `StructuredScope` — virtual threads + `ScopedValue`, both GA | `StructuredTaskScope` |
| **Class-file major** | 69 | 72 |
| **For** | anything published, and any deployment that does not control its JVM | JVM-controlled deployments |

Source: [`docs/release/v0.11.0-release-notes.md`](../release/v0.11.0-release-notes.md) (quoted).

**Take `0.11.0` unless you have a reason not to.** The preview line exists to absorb JDK API churn
ahead of the LTS it converges into, not to be the better artifact.

Why this matters to you rather than to us: `--enable-preview` is not a per-library opt-in. It is a
whole-compilation and whole-JVM flag, and the bytecode it produces is pinned to one exact class-file
major. Had the distributed artifact carried that stamp, **you** would have to build and run your
entire application with the flag and pin to our exact JDK. As of `0.11.0` it carries none of it —
measured at the cut, not asserted: 2 286 classes of ours across the eight published modules,
class-file major 69, zero preview-stamped. **Those figures describe `0.11.0`, not this branch** — you
are reading the `preview` line, whose artifact is `eu.exeris.preview:*:0.11.1` and is preview-stamped by
definition. See [`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md). A CI gate reads the published jars and fails the build on
any preview stamp (`tools/preview-bytecode-scan/`); it scans 15 185 classes in total, because a
preview-stamped class vendored into the shaded diagnostics CLI would break you exactly as one of ours
would. The decision and its reasoning are ADR-066 ([`docs/adr/`](../adr/)).

---

## JVM baseline

- **JDK 25 LTS or newer** for the distributed line — but **not on this branch**: `preview` compiles
  with `--enable-preview`, which javac only accepts when `--release` equals the running JDK, so it
  needs the exact JDK named in `PREVIEW-TRACK.md` (28 today) and no other. The JDK baseline moved 26 → 25, which for a
  consumer is a *widening*: anything that ran on 26 still runs, and LTS-only environments become
  reachable. Nothing in the kernel used a JDK-26-only API.
- **No `--enable-preview`.** See above.
- **`--enable-native-access=ALL-UNNAMED`** when the Panama FFM paths run — the native TCP transport
  and the OpenSSL-backed crypto engine. This is the flag the kernel's own build passes to its test
  JVM (root [`pom.xml`](../../pom.xml), `jvm.args`).

> **Gap.** [`docs/operations/jvm-flags-baseline.md`](../operations/jvm-flags-baseline.md) covers
> container awareness, GC, large pages, and CDS/AOT — but not native-access or preview. This page is
> currently the only place the consumer-facing flag requirement is written down. Use that doc for
> everything else about JVM tuning.

---

## Coordinates

**The two lines differ by groupId, not by version.** The distributed line is `eu.exeris:*`; this
branch, the `preview` line, publishes `eu.exeris.preview:*` at the same plain version. Opting in is
therefore an explicit coordinate change rather than something a range can drift onto — see
[`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md) for why that was chosen over a `-preview` version
suffix.

**Every snippet below shows the distributed line's coordinates** — groupId `eu.exeris`, and a
`0.11.0-SNAPSHOT` version from before either line was cut. On this branch substitute
`eu.exeris.preview`, and the version you are actually resolving — `0.11.1` to pin this line's latest
cut, `0.12.0-SNAPSHOT` if you built this branch yourself. They are quoted verbatim from the BOM README so they stay correct at their
source; qualifying them here rather than rewriting them is what keeps this page from drifting out of
step with it.

groupId is `eu.exeris` on the distributed line. Import the BOM to inherit validated versions:

Source: `exeris-kernel-bom/README.md:16-23` (adapted — `${project.version}` replaced by a literal;
that property only resolves inside this reactor).

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>eu.exeris</groupId>
            <artifactId>exeris-kernel-bom</artifactId>
            <version>0.11.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then one dependency:

```xml
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
</dependency>
```

### What that one dependency gets you

`exeris-kernel-community` brings `exeris-kernel-core` and `exeris-kernel-spi` transitively, along
with the Community providers for every subsystem. You do not declare them separately.

> **How this is verified.** `exeris-kernel-diagnostics-cli` is a module in this reactor whose only
> kernel dependency is `exeris-kernel-community` (`exeris-kernel-diagnostics-cli/pom.xml:34-40`,
> comment: *"Brings spi + core + the Community providers to bootstrap and introspect"*) and whose
> `main()` constructs `KernelBootstrap`
> (`exeris-kernel-diagnostics-cli/src/main/java/eu/exeris/kernel/diagnostics/cli/DiagnosticsCli.java:75-88`).
> It is compiled and tested by `mvn clean install` on every run.
>
> It calls `inspect()` rather than `boot()`. `boot()` end-to-end over a real socket is covered
> separately by `KernelBootstrapHttpEngineFixtureIntegrationTest` in `exeris-kernel-community`.
>
> **Not verified:** resolution from a remote repository — see *Resolving today* below.

### What the BOM manages

`exeris-kernel-spi`, `-core`, `-community`, `-community-testkit`, `-community-kafka`, and
`-tck` (both the plain jar and the `tests` / `test-jar` variant). Source:
`exeris-kernel-bom/pom.xml:50-88`.

`exeris-kernel-diagnostics-cli` is a reactor module but is **not** exported by the BOM.

### Test-scope coordinates

For testing an application against a real booted kernel — see
[02 — Build an Application](./02-build-an-application.md):

```xml
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community-testkit</artifactId>
    <scope>test</scope>
</dependency>
```

The fixtures live in that module's **main** sources, so this is a plain `test`-scoped dependency —
no classifier. The BOM does not set a scope, so you declare it (as
`exeris-kernel-community/pom.xml:125-129` does).

For binding the TCK when implementing a provider — see
[03 — Implement a Provider](./03-implement-a-provider.md):

Source: `exeris-kernel-community/pom.xml:93-100` (quoted).

```xml
<!-- TCK abstract test classes -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-tck</artifactId>
    <classifier>tests</classifier>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

The `classifier` and `type` are both required: the abstract TCK suites live in the TCK module's
*test* sources and are published as a test-jar.

---

## Resolving today

**`0.11.0` is released on the distributed line, and this line's latest cut is `0.11.1`** — under
`eu.exeris.preview`, so the two do not collide. The publish target is GitHub Packages (`pom.xml`,
`distributionManagement`), which requires authentication, so a consumer still has to configure that
repository before either line resolves.

So the path that actually works right now is local:

```bash
git clone git@github.com:exeris-systems/exeris-kernel.git
cd exeris-kernel
mvn clean install
```

then depend on what it installed — `eu.exeris:*:0.11.0` on the distributed line,
`eu.exeris.preview:*:0.12.0-SNAPSHOT` here, because this branch carries the next `-SNAPSHOT` between
cuts (see [`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md)) — which resolves from your local repository.
To pin a *released* preview artifact instead, use the latest cut, `eu.exeris.preview:*:0.11.1`.
Nothing is published to Maven Central.

---

## The first failure you are likely to hit

`EX-CFG-0001` — no `ConfigProvider` on the classpath. The kernel resolves one via `ServiceLoader`
during bootstrap and fails hard if none is found; there is no built-in default.

Source: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/KernelBootstrap.java:410-420`
(quoted).

```java
return ServiceLoader.load(ConfigProvider.class, classLoader)
        .stream()
        .map(ServiceLoader.Provider::get)
        .max(Comparator.comparingInt(ConfigProvider::priority))
        .orElseThrow(() -> new BootstrapException(
                "No ConfigProvider found on classpath. "
                + "Add exeris-kernel-community (SimpleFileConfigProvider) "
                + "or exeris-kernel-enterprise to the runtime classpath. "
                + "[EX-CFG-0001]"));
```

Adding `exeris-kernel-community` fixes it.

> **The message names a class that does not exist.** There is no `SimpleFileConfigProvider`. The
> class Community actually registers is
> `eu.exeris.kernel.community.config.CommunityConfigProvider`. If you searched for the name in the
> error and found nothing, that is why. Tracked as a follow-up; the message is stale, the fix is not.

---

## See also

- [02 — Build an Application](./02-build-an-application.md)
- [03 — Implement a Provider](./03-implement-a-provider.md)
- [`docs/support-matrix.md`](../support-matrix.md) — supported database, broker, TLS, and HTTP versions
- [`docs/stability-matrix.md`](../stability-matrix.md) — how far you can lean on each SPI surface
- [`docs/operations/jvm-flags-baseline.md`](../operations/jvm-flags-baseline.md) — GC, container, and CDS/AOT tuning
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — building this repository from source
