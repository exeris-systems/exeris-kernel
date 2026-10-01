---
title: "Platform and Dependencies"
type: tutorial
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Platform and Dependencies

**Audience:** anyone consuming Exeris Kernel as a dependency — whether you are building an
application on it or implementing a provider for it.

> **Verified against** `0.12.0` at commit `38208e69`, 2026-09-08. Every snippet below is
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

v0.12 ships two artifacts. They carry the **same kernel** — same SPI, same subsystems, same tests —
and differ in one axis plus the build that follows from it.

| | `eu.exeris:*:0.12.0` | `eu.exeris.preview:*:0.12.0` |
|:--|:--|:--|
| **JDK** | **25 LTS** | newest available — JDK 28 today |
| **`--enable-preview`** | **not required, and not imposed on you** | required, by definition |
| **Structured concurrency** | `StructuredScope` — virtual threads + `ScopedValue`, both GA | `StructuredTaskScope` |
| **Class-file major** | 69 | 72 |
| **For** | anything published, and any deployment that does not control its JVM | JVM-controlled deployments, and early access to what the next LTS will carry |

Source: [`docs/release/v0.12.0-release-notes.md`](../release/v0.12.0-release-notes.md) (quoted).

**Take `0.12.0` unless you have a reason not to.** The preview line exists to absorb JDK API churn
ahead of the LTS it converges into, not to be the better artifact.

Why this matters to you rather than to us: `--enable-preview` is not a per-library opt-in. It is a
whole-compilation and whole-JVM flag, and the bytecode it produces is pinned to one exact class-file
major. Had the distributed artifact carried that stamp, **you** would have to build and run your
entire application with the flag and pin to our exact JDK. As of `0.12.0` it carries none of it —
measured on this tree, not asserted, by running `tools/preview-bytecode-scan/preview-bytecode-scan.sh`
against a full reactor build: zero preview-stamped classes, class-file major 69 throughout, across
the eight published modules. That same script is the CI gate: it reads the published jars and fails
the build on any preview stamp, scanning every class in every published jar — including the shaded
diagnostics CLI — because a preview-stamped class vendored in there would break you exactly as one of
ours would. The exact owned/scanned class counts depend on `target/` holding only the artifacts of
one clean full build; a stale `maven-shade-plugin` backup jar left behind by a prior partial build is
not excluded by the scan and will inflate both counts, so this guide does not quote an exact figure —
treat any exact count you see elsewhere as a snapshot of one specific clean build, not a standing
invariant. The decision and its reasoning are ADR-066 ([`docs/adr/`](../adr/)).

---

## JVM baseline

- **JDK 25 LTS or newer** for the distributed line. The JDK baseline moved 26 → 25, which for a
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

groupId is `eu.exeris` throughout. Import the BOM to inherit validated versions:

Source: `exeris-kernel-bom/README.md`, its "Usage" example (adapted — `${project.version}` replaced
by a literal; that property only resolves inside this reactor).

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>eu.exeris</groupId>
            <artifactId>exeris-kernel-bom</artifactId>
            <version>0.12.0</version>
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
with the Community provider classes for every subsystem, registered via `ServiceLoader`. Most
provider runtime dependencies flow transitively too, but the PostgreSQL JDBC driver behind the
storage provider is declared `<optional>true</optional>` in `exeris-kernel-community/pom.xml` and
must be added separately by any consumer using Postgres-backed storage — it is the one exception.

> **How this is verified.** `exeris-kernel-diagnostics-cli` is a module in this reactor whose only
> kernel dependency is `exeris-kernel-community` (`exeris-kernel-diagnostics-cli/pom.xml`,
> comment: *"Brings spi + core + the Community providers to bootstrap and introspect"*) and whose
> `main()` constructs `KernelBootstrap`
> (`exeris-kernel-diagnostics-cli/src/main/java/eu/exeris/kernel/diagnostics/cli/DiagnosticsCli.java`,
> method `main`). It is compiled and tested by the default CI build (`mvn clean verify`, no module
> filter — [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml)) on every push.
>
> It calls `inspect()` rather than `boot()`. `boot()` end-to-end over a real socket is covered
> separately by `KernelBootstrapHttpEngineFixtureIntegrationTest` in `exeris-kernel-community`.
>
> **Not verified:** resolution from a remote repository — see *Resolving today* below.

### What the BOM manages

`exeris-kernel-spi`, `-core`, `-community`, `-community-testkit`, `-community-kafka`, and `-tck`
(the plain jar only — see *Test-scope coordinates* below for what changed about `-tck` in `0.12.0`).
Source: the `<dependencyManagement>` block in `exeris-kernel-bom/pom.xml`.

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
no classifier. The BOM does not set a scope, so you declare it (as the `exeris-kernel-community-testkit`
dependency in `exeris-kernel-community/pom.xml` does).

For binding the TCK when implementing a provider — see
[03 — Implement a Provider](./03-implement-a-provider.md):

Source: `exeris-kernel-community/pom.xml` (quoted), the `exeris-kernel-tck` dependency block.

```xml
<!-- TCK abstract test classes -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-tck</artifactId>
    <scope>test</scope>
</dependency>
```

**No `classifier` or `type` any more — this changed in `0.12.0`.** Through `0.11.x` the abstract TCK
suites lived in the TCK module's *test* sources and were published as a `tests`-classified test-jar,
which is what this snippet used to require. `exeris-kernel-tck` now ships those classes as its plain
main artifact instead (`exeris-kernel-tck/pom.xml`: *"No `test-jar` execution any more, and its
removal is what this change is for. […] Consumers therefore drop
`<classifier>tests</classifier><type>test-jar</type>` and depend on the plain artefact at `test`
scope."*; also
[v0.12.0 release notes](../release/v0.12.0-release-notes.md), "`exeris-kernel-tck` publishes its
contract as its main artifact instead of hiding it under a classifier"). A dependency still carrying
the old classifier/type pair will fail to resolve against `0.12.0`, since the BOM only manages the
plain-jar coordinate (see *What the BOM manages* above).

---

## Resolving today

**No `0.12.0` has been released.** The reactor version on this branch is already the plain `0.12.0`
release coordinate rather than a `-SNAPSHOT` — the milestone's version-flip commit has landed — but
no `v0.12.0` git tag exists in this repository yet, and the
[v0.12.0 release notes](../release/v0.12.0-release-notes.md) say so explicitly: *"It has still
published nothing to Central — the path is proven, not exercised."* / *"Central publication — the
workflow is gated and dry-runnable; the first real upload has not happened."* Central publication is
tag-triggered ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) and, even once
uploaded, requires a manual Publish in the Central portal before it becomes resolvable
(`autoPublish=false`).

Ordinary pushes to this branch also publish to GitHub Packages
(`pom.xml`, `distributionManagement`; [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml)),
which requires authentication — but that path is designed around the SNAPSHOT publish that a
long-lived development branch normally carries, and this guide does not track whether a given
`0.12.0` build has been pushed through it. Don't rely on it resolving.

So the path that is verified to work right now is local — a full reactor build off this command:

```bash
git clone git@github.com:exeris-systems/exeris-kernel.git
cd exeris-kernel
mvn clean install
```

then depend on `0.12.0`, which resolves from your local repository (`~/.m2`). (The exact
preview-bytecode-scan class counts such a build produces depend on `target/` holding only the
outputs of that one clean build, per the caveat under *Which artifact line* above — they are not a
fixed number to quote independent of the build that produced them.)

---

## The first failure you are likely to hit

`EX-CFG-0001` — no `ConfigProvider` on the classpath. The kernel resolves one via `ServiceLoader`
during bootstrap and fails hard if none is found; there is no built-in default.

Source: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/bootstrap/KernelBootstrap.java`,
method `resolveConfigProvider()` (quoted).

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
