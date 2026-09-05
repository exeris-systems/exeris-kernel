# Contributing to Exeris Kernel

Welcome. This document is the **minimum viable onboarding guide** for contributors to
`exeris-kernel`. Read it before opening a PR — it will save you a review cycle.

The Exeris Kernel is not a standard Java application. It is a system-level runtime engineered
for zero-copy, zero-allocation hot paths. The patterns used here (Panama FFM, `LoanedBuffer`,
`ScopedValue`, off-heap memory) are deliberately non-standard. This document exists to lower
the barrier to contribution, not to enforce bureaucracy.

---

## Licence and the Contributor Licence Agreement

The kernel is **Apache License 2.0**, unmodified — see [`LICENSE`](LICENSE) and
[`LICENSING.md`](LICENSING.md). Earlier releases carried the Commons Clause; they no longer do.

Contributions are accepted under a **Contributor Licence Agreement**, and it is worth saying
plainly what that is for rather than leaving you to infer it.

Exeris is open-core: the kernel here is Apache-2.0, and a separate enterprise tier is
commercial. Apache-2.0 section 5 makes your contribution inbound-equals-outbound by default —
it arrives under the same licence the project publishes — which is exactly right for this
repository and does **not** give the project the right to place your code in the commercial
tier. The CLA does. That is the whole of what it asks for beyond the licence you already grant.

What it does **not** ask for:

- **It does not transfer your copyright.** You keep it. The CLA is a non-exclusive licence,
  not an assignment. That is a deliberate construction and not merely a soft option: under
  Polish law an assignment of economic copyright requires written form on pain of nullity
  (art. 53 of the copyright act), which a click-through cannot satisfy, while a non-exclusive
  licence carries no form requirement (art. 67(5), read the other way round). A CLA works
  where a contributor assignment agreement would not.
- **It does not take your code out of Apache-2.0.** The agreement carries a *promise back*:
  your contribution remains available under the licence in force on the day you submitted it.
  Nothing you contribute can be removed from the open kernel later.

We would rather state the asymmetry than pretend it is not there: the project gets a right you
do not get in return. That is the honest shape of open-core, and if it is not acceptable to
you, that is a reasonable position and we would rather know before you spend time on a patch.

The agreement is with **Arkadiusz Przychocki**, trading as Exeris Systems, and carries a
succession clause so the rights pass to the company on incorporation — a licence is granted to
a person, natural or legal, and a GitHub organisation is neither. `NOTICE` and the per-file
headers name the project rather than the person; the CLA and `TRADEMARK.md` name the person,
because those two are the instruments that need one.

**Sign-off.** Alongside the agreement, an external contribution carries a `Signed-off-by:` trailer
(`git commit -s`). It is a Developer Certificate of Origin sign-off, and it answers a different
question from the agreement: it certifies that you have the right to submit the work under the
licence this repository publishes, and it grants nothing at all. The two compose — origin on every
commit, rights once. Organisation members are exempt from the trailer, not from being accountable
for what they merge.

> **Status:** the agreement text is being finalised and the signing flow is not yet wired up.
> Until it is, a pull request will not be blocked on it — but a contribution merged in the
> meantime is merged on the understanding above, and you will be asked to sign retroactively.
> If that is not acceptable, say so on the PR and we will hold it.

**AI provenance.** Exeris is built with AI assistance as a matter of course, and states the terms
rather than hiding them. They are
[`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md),
and they are short:

- An AI-assisted commit keeps its `Co-authored-by:` trailer. Stripping it is a defect; adding it
  where no AI was involved is a lie, and is treated the same.
- **A named human is accountable for every line.** You must be able to explain and defend any part
  of the change in review. "The agent produced it" is not an answer — it is the reason the question
  is being asked.
- **Agents do not open pull requests, file issues or post comments without a human author.**
  Automated *review* comments are fine; automated *contributions* are not.
- A pull request states the commands run after the last push. A green default build says nothing
  about the tagged gates, and a skip-flagged build says nothing about lint.
- AI-generated tests that assert nothing observable are rejected. Tests follow this repository's
  philosophy — TCK-first, semantics over volume — not a line count.
- The description is what you would have written unaided: what it does, what it costs, what it does
  not cover. Not a transcript.

---

## Prerequisites

### Java Version

**JDK 25 LTS is the baseline on this line; a newer JDK also works.** The line is preview-clean
throughout (ADR-066 and its Amendment A1): nothing compiles with `--enable-preview`, main sources,
test sources and TCK fixtures alike. That is what makes a newer JDK usable — the flag is legal only
when `--release` equals the running JDK, so while it was set anywhere, JDK 25 was the only JDK that
could build the repository. The `preview` branch is the opposite — newest JDK, preview features on —
and ships separately as `1.0-preview`.

| Feature                            | JEP       | Status in JDK              | Used in Kernel                                    |
|:-----------------------------------|:----------|:---------------------------|:--------------------------------------------------|
| Virtual Threads                    | JEP 444   | Stable (JDK 21)            | Every request-handling path                       |
| Structured Concurrency             | GA / preview | **Track-dependent** — see ADR-066 | `StructuredScope` on `main`; `StructuredTaskScope.open(Joiner)` on the `preview` branch |
| Scoped Values                      | JEP 506   | Preview → finalising       | `KernelContext`, `StorageContext`, `PrincipalContext` |
| Foreign Function & Memory (FFM)    | JEP 454   | Stable (JDK 22)            | OpenSSL bindings, off-heap I/O, `io_uring`        |
| Flexible Constructor Bodies        | JEP 513   | **Closed / Delivered (JDK 25)** | Field pre-init before `super()` in value-ready types |
| Valhalla Value Classes (prep)      | JEP 401   | Early Access preview       | **Not yet used.** All data carriers (`record`, `final class`) are designed to be migration-ready: no `synchronized`, no `System.identityHashCode()`, no identity `==` on domain objects. C2 JIT Escape Analysis scalarises them on hot-paths today. |
| Lazy Constants                     | JEP 526   | Delivered in JDK 26 — **not available on this line** | Not used on `main`; the JDK 25 baseline predates it |

The root POM sets `--enable-preview` nowhere — not on main sources, not on `default-testCompile`,
not on a surefire or JMH JVM. Do not reintroduce it in any scope on this line. In main sources the
Preview-Bytecode Gate will catch it: it reads the published jars and fails on any class stamped
`minor_version 0xFFFF`, which is exactly what a consumer would trip over. In test sources nothing
would catch it, and the cost is quieter — the whole repository becomes buildable by one exact JDK
again, and the GA line reacquires a dependency on an API that is still changing shape across
previews.

**Recommended toolchain:**
```
sdk install java 26-open   # SDKMAN (Linux/macOS)
# or download from https://jdk.java.net/26/
```

Verify:
```powershell
java -version
# Expected: openjdk version "26" or higher
```

### Native Libraries (Linux required for full test suite)

The `exeris-kernel-community` and `exeris-kernel-core` TLS tests call into OpenSSL 3.x via
Panama FFM. On **Linux**, the tests expect `libssl.so.3` on `LD_LIBRARY_PATH`. On **macOS**,
`libssl.3.dylib` must be available (via Homebrew `openssl@3`). On **Windows**, only unit tests
that do not invoke FFM symbols will pass — TLS integration tests are gated by the
`os.name` system property and skip automatically on Windows.

```bash
# Linux (Debian/Ubuntu)
sudo apt-get install libssl-dev

# macOS
brew install openssl@3
export DYLD_LIBRARY_PATH="$(brew --prefix openssl@3)/lib:$DYLD_LIBRARY_PATH"
```

### Other Tools

| Tool       | Minimum Version | Purpose                                      |
|:-----------|:----------------|:---------------------------------------------|
| Maven      | 3.9+            | Build system                                 |
| Docker     | 24+             | Testcontainers-backed tests (Postgres, Kafka) |
| Podman     | 4.x (alternative) | Drop-in Docker replacement                 |
| `jcmd`     | bundled with JDK | JFR snapshot inspection                    |
| JDK Mission Control (JMC) | 9.0+ | Visual JFR analysis (optional)        |

---

## Static Analysis (SonarQube Cloud)

Analysis runs **from CI**, as a step in `build-and-verify` after `mvn clean verify -P coverage`, so it
consumes the compiled classes and JaCoCo XML that build already produced.

**Configuration lives in the POMs, and there is no `sonar-project.properties`.** The step invokes the
SonarQube Scanner for Maven, which does not read that file and does not need most of what one would
hold: `projectVersion`, source encoding, the Java release, source and test roots, compiled output and
the analysis classpath all come from the reactor. Only what cannot be derived is declared, in the root
POM's `<properties>`: project identity, exclusions, the coverage report path, the new-code reference
branch and the `java:S2187` suppression. One module overrides its own classification —
`exeris-kernel-tck` sets `sonar.sources` empty and puts both source roots under `sonar.tests`, because
its main sources are contract tests and Sonar keys production rules off `sonar.sources` alone.

Two rules for anything you add there. Write a coverage or report path as
`${project.build.directory}/...`, never as a `**/` glob: a glob resolves against each module's base
directory, so the reactor root matches every module's file at once and imports all of them. And do not
set `sonar.projectName`; a property in the root POM is inherited by every module, which labels all
eleven identically in the log, and it does not rename an existing SonarQube Cloud project anyway.

Two things had to be true in the SonarQube Cloud project before any of this worked, and neither is
expressible in this repository. Both are **done**, recorded here because they are invisible from the
code and the next person to wonder why a fork's analysis skips will need them:

1. **Automatic Analysis is OFF.** It is mutually exclusive with CI-based analysis: leave it on and
   SonarQube Cloud *rejects* the CI submission. Requires an organisation administrator.
2. **`SONAR_TOKEN` exists as an organisation secret.** The step is guarded on it, so a pull request
   from a fork (which gets no secrets) skips the analysis rather than failing.

The analysis step carries `continue-on-error: true`, and it stays, because enforcement does not run
through it. The step sits in `build-and-verify`, which every other job depends on, so a SonarQube
outage must not be able to fail it. The quality gate is enforced instead by the check SonarQube Cloud
publishes once it has processed the report — `SonarCloud Code Analysis` — which is a **required
status check on `main`**, pinned to that app so nothing else can satisfy it by name. The two fit
together: a red gate blocks the merge without a SonarQube outage taking the build job, and the eight
jobs downstream of it, with it.

This also makes the step's own semantics harmless. It does not wait for the verdict
(`sonar.qualitygate.wait` is set nowhere) and exits as soon as the report is uploaded, so its success
means "submitted", not "passed". That mattered while it was the only signal; it does not now.

The same three contexts are required on `development/**`, as a pattern, so the next milestone's
branch inherits it rather than needing the rule re-created. Two settings differ from `main` there on
purpose: no approving-review requirement, and "up to date before merging" off, because enforcing that
against a branch with many open pull requests serialises merges for little gain. The `preview` branch
keeps its own rule and does not require the SonarQube check — that track builds on a newest-JDK line
with `--enable-preview`, and what the analyser reports there has not been measured.

The scanner log used to carry two warnings, `Unresolved imports/types have been detected` and `Use of
preview features have been detected`, and both are gone. Neither was a defect in the code: the same
1374 Java files are parsed now as then, so this is not a narrower analysis. The first is the reason the analysis step runs `verify` in the same Maven command as
`sonar:sonar`. Invoked alone, the goal starts a session where no module has been packaged, so Maven
cannot resolve a reactor sibling to a jar and falls back to the local repository, which in CI holds
none of them. Maven announces it before the goal starts (`could not be resolved at this point of the
build but seem to be part of the reactor ... Try running the build up to the lifecycle phase
package`), and the fingerprint is unmistakable: the warning fired in every module with a reactor
dependency and in neither of the two without one. The preview warning disappeared with it, which was
not predicted — it had fired in `exeris-kernel-spi` too, where the other never did — and the mechanism
behind that half is not established, only that it is empirically tied to the same change. It was never
a risk either way: javac makes a preview feature at `--release 25` without `--enable-preview` a
compile error rather than a warning, and the preview-bytecode gate reads major 69 with no stamp.

Two things about the wrong turn are worth keeping, because both cost a cycle. A local dump can disagree with
CI here for a reason that has nothing to do with the configuration — a previous `mvn clean install`
leaves the sibling jars in `~/.m2`, so locally they resolve and the problem is invisible. And the
obvious probe does not work: the warning says `Enable DEBUG mode to see them`, a run with
`-Dsonar.verbose=true` was made, the property reached the scanner, and the log came back with zero
DEBUG lines and the same unnamed warning.

## Build & Test

### The Golden Command

```bash
mvn clean install
```

This is the **only** command that counts. `mvn clean compile` is not sufficient — it skips:
- PMD static analysis (Priority 1-3 rules including banned `ThreadLocal` and `ExecutorService` detection)
- TCK execution in `LeakDetectionMode.PARANOID`
- SPI isolation verification (ensures `exeris-kernel-spi` has no implementation-specific imports)
- JFR-based zero-allocation validation on the `wrap()`/`unwrap()` hot path

**Expected output (clean build):**
```
[INFO] exeris-kernel-spi .......................... SUCCESS
[INFO] exeris-kernel-core ......................... SUCCESS
[INFO] exeris-kernel-community .................... SUCCESS
[INFO] exeris-kernel-tck .......................... SUCCESS
[INFO] BUILD SUCCESS
```
> `exeris-kernel-enterprise` is not part of this open-source reactor. It ships as a separate
> closed-source distribution. Running `mvn clean install` here will not build or require it.

### Running a Single Module

```bash
mvn clean install -pl exeris-kernel-core -am
# -am = also build dependencies (spi)
```

### Skipping Native Tests on Windows

If you are on Windows and do not have OpenSSL available, FFM-based TLS tests will be auto-skipped.
To confirm which tests were skipped:

```powershell
mvn clean install -Dexeris.native.skip=true
```

### TCK Paranoid Leak Mode

The TCK runs with `LeakDetectionMode.PARANOID` by default. This mode verifies that every
`LoanedBuffer` acquired during a test is released before the test method exits. A failed
`LeakDetectedError` means a buffer was not returned to the pool — fix the lifecycle, not the test.

To run only the TCK:
```bash
mvn test -pl exeris-kernel-tck
```

### Supply-Chain Gate (SBOM + reproducible builds)

Every module emits a CycloneDX SBOM at `package`, attached under classifier `cyclonedx` so
`mvn deploy` publishes it beside the jar. Two checks guard it, and both run locally:

```bash
# Fails on a plugin that cannot produce reproducible output — reads the build PLAN, so it needs
# no artifacts and catches the problem before anything is published.
mvn -B org.apache.maven.plugins:maven-artifact-plugin:3.6.0:check-buildplan

# Fails on an SBOM that is present and wrong: empty component list, stale version, restored
# random serial. Needs a full `mvn package` first.
tools/sbom-gate/sbom-gate.sh
```

To check reproducibility itself, build twice and compare — the property is that the bytes match,
so that is what the check has to look at:

```bash
mvn -q clean package -DskipTests && find . -name '*.jar' -path '*/target/*' | sort | xargs sha256sum > /tmp/run1
mvn -q clean package -DskipTests && find . -name '*.jar' -path '*/target/*' | sort | xargs sha256sum > /tmp/run2
diff /tmp/run1 /tmp/run2   # must be empty
```

Two things break this, both easy to do by accident:

- **Adding a plugin without a version.** Unversioned plugins resolve through Maven's super-POM, so
  their versions come from whichever Maven is on the machine, and the published bytes then change
  without a commit changing. `check-buildplan` catches it.
- **Writing the build timestamp into an artifact.** `${maven.build.timestamp}` in a filtered
  resource, a manifest entry, or a generated file defeats `project.build.outputTimestamp` for that
  module only — so the reactor stays reproducible everywhere except the one place that regressed.
  The double-build diff above is what finds it.

`project.build.outputTimestamp` (root `pom.xml`) is bumped at each release cut. A stale value is
not a defect: what makes the output reproducible is that the value is fixed, not that it is recent.

### Exercising the release path locally

The `release` profile is what a Maven Central release runs, and it is worth running before you need
it — its failures are per-module and per-file, and Central discovers them on an **immutable**
channel. Signing needs a key, so use a throwaway one in an isolated keyring rather than your own:

```bash
export GNUPGHOME=$(mktemp -d) && chmod 700 "$GNUPGHOME"
gpg --batch --passphrase testpass --quick-gen-key 'Test <test@invalid.local>' rsa3072 sign 1d

# verify, NOT deploy: builds and signs everything Central would receive, touching nothing remote
MAVEN_GPG_PASSPHRASE=testpass mvn -P release clean verify -DskipTests

tools/release-readiness/release-readiness.sh
```

The gate asserts that every coordinate has a pom, jar, sources jar, javadoc jar and SBOM, and that
each verifies against the key. It reports what it deliberately skipped rather than staying silent —
`exeris-kernel-tck` is currently held back from Central, and the root POM records why.

Two things this profile does that the ordinary build does not, both of which have already bitten:

- `central-publishing-maven-plugin` declares `<extensions>true</extensions>` and takes over
  `deploy`, which sets `maven.deploy.skip`. That in turn made `cyclonedx` skip itself
  (`skipNotDeployed` defaults to true), so the release build emitted signed artifacts and no SBOMs
  while the ordinary SBOM gate stayed green. Anything you add under `-P release` needs checking
  under `-P release`; the default build does not cover it.
- A module with no `src/main` produces no sources or javadoc jar, and Central requires both per
  artifact. That is why `exeris-kernel-tck` is excluded rather than published empty.

**Reproducibility on this path is a property of CLEAN builds, and the release workflow depends on
it.** Measured by building `-P release` twice: of 69 files, 45 differ and every one is a `.asc` —
OpenPGP signature packets carry a creation timestamp, so re-signing the same content produces
different, equally valid bytes. **Zero jars differ, javadoc included**: `maven-javadoc-plugin`
passes `-notimestamp` once `project.build.outputTimestamp` is set, so the property CONTRIBUTING
documents for the ordinary build holds here too.

Build a second time over a **dirty** `target/` and it does not.
`exeris-kernel-diagnostics-cli` is shaded, and re-running shade over an already-shaded jar produces
different bytes. The release workflow therefore uses `clean` on its deploy step and then asserts
that every jar, pom and SBOM is byte-identical to the one the gates and the provenance attestation
covered — because Maven re-runs the lifecycle up to `deploy` and there is no way to upload
previously built artifacts (central-publishing stages its bundle during the deploy phase; nothing
exists before it).

> **Adding a script under `tools/`:** set its mode through git, not only through the filesystem —
> `git update-index --chmod=+x <path>`. This repository is commonly cloned with
> `core.fileMode=false`, which makes `chmod +x` invisible to git: the script runs perfectly for you
> and lands in the commit as `100644`, so CI is the first thing to discover it with
> `Permission denied` and exit 126. Check with `git ls-files -s tools/**/*.sh` — every gate there
> should read `100755`.

---

## Local Environment

**There is no local stack to start.** The repository has no `docker-compose.yml`, and nothing needs
one. Tests that require Postgres or Kafka start their own containers through Testcontainers and tear
them down again — so the only prerequisite is a running **Docker or Podman** daemon. This is a direct
consequence of ADR-001 (Cloud Agnostic Infrastructure): no cloud account, no managed service, no
port-forwarding magic.

Container images are pinned in the test sources, e.g.
`new PostgreSQLContainer<>("postgres:16")` in
`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/persistence/CommunityPersistenceIsolationLeakTckIT.java:37`.

### You do not need a daemon for the default build

Container-backed tests are tagged (`integration`, `continuity`, `stress`) and are **excluded from
`mvn clean install`**. A machine with no Docker daemon still gets a green default build — and that
green build is not evidence those tests pass. Run them explicitly when you touch what they cover:

```bash
mvn -pl <module> -DincludedGroups=integration -DexcludedGroups= test
```

The exclusion list is per-module — see the `excludedGroups` property in each module's `pom.xml`
(e.g. `exeris-kernel-community-kafka/pom.xml:31`) for which tags that module holds back and why.

### Consuming the kernel rather than contributing to it

Different requirements — notably no `--enable-preview` — and different coordinates. See
[docs/guides/01-platform-and-dependencies.md](docs/guides/01-platform-and-dependencies.md).

---

## JFR Inspection

Exeris emits custom JFR events under the `Exeris Kernel` category. They are the primary observability
mechanism — not log files.

### Starting a JFR Recording

```bash
# Attach to a running JVM (replace <pid> with the actual process ID)
jcmd <pid> JFR.start name=exeris-debug settings=profile duration=60s filename=debug.jfr

# Or start the JVM with recording enabled from the beginning:
java -XX:StartFlightRecording=name=boot,settings=profile,filename=boot.jfr \
     -jar exeris-kernel-core/target/exeris-kernel-core.jar
```

### Locating Exeris Events in JMC

1. Open `debug.jfr` in **JDK Mission Control (JMC)**.
2. Navigate to **Event Browser** → expand **Exeris Kernel**.
3. Key event categories:

| JFR Category                  | Event Class                                  | What it tells you                               |
|:------------------------------|:---------------------------------------------|:------------------------------------------------|
| `Exeris Kernel / Memory`      | `TelemetryJfrEvents.*`                       | Off-heap allocation sample (1% rate)            |
| `Exeris Kernel / Memory`      | `TelemetryJfrEvents.MemoryExhaustionJfrEvent`| Pool exhausted — trigger for load shedding      |
| `Exeris Kernel / Memory`      | `LeakDetectedEvent`                          | Unclosed `LoanedBuffer` — always a bug          |
| `Exeris Kernel / Security`    | `PrincipalBoundEvent`                        | Successful auth + scope bind                    |
| `Exeris Kernel / Security`    | `SecurityContextMissing`                     | Gate drop — token invalid or no provider        |
| `Exeris Kernel / Transport`   | `TransportBindEvent`                         | Transport successfully bound on port            |
| `Exeris Kernel / Bootstrap`   | `BootstrapJfrEvents.SubsystemInitializedEvent` | Per-subsystem initialization duration         |
| `Exeris Kernel / Crypto`      | `TlsHandshakeEvent`                          | Handshake duration + cipher suite               |
| `Exeris Kernel / Crypto`      | `TlsHandshakeFailureEvent`                   | Handshake failure + peer address                |

### Checking for Heap Allocations on the Hot Path

The TCK automatically validates this during `mvn install`. For manual investigation:

```bash
# Run with GC allocation profiling
java -XX:StartFlightRecording=settings=profile \
     -jar exeris-kernel-core/target/exeris-kernel-core.jar
```

In JMC, open **Memory** → **Allocation by Thread**. The `wrap()`/`unwrap()` Virtual Thread should
show **0 B/op** on the TLS cipher path — this applies to **both Community and Enterprise** tiers,
because both share the same Core Panama FFM / OpenSSL engine (`CoreOpenSslLoader`, `NativeCipherContext`)
per ADR-008. Any allocation in `eu.exeris.kernel.core.crypto.*` or `eu.exeris.kernel.community.*`
on the TLS cipher hot path is a regression in both tiers.

The remaining heap allocation in Community occurs at the **JDBC layer** (`eu.exeris.kernel.community.persistence.*`)
and is expected — `ResultSet`, DTO, and `String` objects are heap-bound by the JDBC contract.
Enterprise eliminates this via native off-heap DB drivers.

---

## Architectural Guardrails (The Wall)

Before submitting a PR, verify the following:

### Banned Patterns (L0 Enforcement)

| Pattern                                  | Why banned                                           | What to use instead               |
|:-----------------------------------------|:-----------------------------------------------------|:-----------------------------------|
| `ThreadLocal`                            | Memory leaks with Virtual Threads                    | `ScopedValue` (JEP 506)            |
| `ExecutorService` / `Executors`          | Unstructured concurrency, orphan threads             | `StructuredScope` (`main`) / `StructuredTaskScope` (`preview`) |
| `CompletableFuture`                      | Unstructured concurrency                             | `StructuredScope` (`main`) / `StructuredTaskScope` (`preview`) |
| `ByteBuffer` on the cipher path          | Allocates wrapper objects per record                 | `LoanedBuffer` + `MemorySegment`   |
| `Arena.ofConfined()` in business logic   | Bypasses `WatermarkManager`                          | `MemoryAllocator.allocate()`       |
| `sun.misc.Unsafe`                        | Unsafe, no arena bounds checking                     | FFM API / `VarHandle`              |
| Spring / Guice / Jakarta Inject          | Magic DI, reflection, class churn                    | Pure constructors + `ServiceLoader`|
| `String.formatted()` in exceptions       | Allocates `StringBuilder` on failure path            | `rawArgs[]` primitive layout       |

### The Wall (Module Boundaries)

- `exeris-kernel-spi` must import **nothing** outside `java.*` and `jdk.*`.
- `exeris-kernel-core` must import **nothing** from `community` or `enterprise`.
- `exeris-kernel-community` must import **nothing** from `core` internals (only SPI).
- If you add a new `ExerisKernelException` subclass, you **must** add a `rawArgs` layout
  comment and register the error code in `KernelErrorCodes.java`.
- If you add a new SPI interface, you **must** add a corresponding `Abstract*Tck` class
  in `exeris-kernel-tck` before the PR is mergeable.

### Every New Feature Requires the Test Triad

1. **Unit test** — verifies the class in isolation.
2. **Integration test** — verifies interaction with adjacent components.
3. **TCK expansion** — verifies the SPI contract holds for both Community and Enterprise.

A PR that only adds unit tests is **incomplete** if it touches an SPI boundary.

If the TCK expansion asserts a **deny** or any other negative behaviour, it must also be shown to fail
against a binding that does not implement it — a negative case that would stay green regardless enforces
nothing. See [Proving a New Contract Case Is Not Vacuous](exeris-kernel-tck/README.md#proving-a-new-contract-case-is-not-vacuous).

---

## Getting Help

- **Architecture decisions:** Read `docs/adr/` — specifically ADR-007 (Runtime) and ADR-008 (TLS).
- **Subsystem contracts:** Read `docs/subsystems/` for the specific domain you are touching.
- **Performance contract:** Read `docs/performance-contract.md` before touching any hot path.
- **Error codes:** `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java`
  is the single source of truth — do not add string literals in exception constructors.

---

## Off-Heap Memory — Critical Contributor Rules

Every contributor touching `MemoryAllocator`, `LoanedBuffer`, or `MemorySegment` MUST understand these rules.
Violations do not produce obvious Java exceptions — they cause silent leaks, `SIGSEGV`s, or `FAILED` state.

### Rule 1: Silent Leaks — Always use `try-with-resources`

A `LoanedBuffer` that is never `close()`d does **not** throw `OutOfMemoryError`. It silently exhausts
the off-heap budget until `WatermarkManager` signals `EX-MEM-1001`. Always:

```java
try (LoanedBuffer buffer = allocator.allocate(AllocationHint.MEDIUM)) {
    // use buffer
}  // close() called automatically — ref-count decremented
```

### Rule 2: One Close Per Acquire — Not Per Reference

**Corrected against the implementation.** This rule previously said that calling
`LoanedBuffer.close()` twice decrements `refCount` below zero and yields a use-after-free. It does
not, and it never did: `AbstractLoanedBuffer.close()` opens with `if (prev <= 0) { return; }` inside
its CAS loop, so a close on an already-released buffer is a no-op. That matches the SPI contract —
`LoanedBuffer.close()`'s javadoc requires idempotence in so many words — and it is **executable**,
not merely documented: `AbstractLoanedBufferTest` ("Double close is idempotent — does not release
twice") and `CommunityLoanedBufferTest` ("double close() after single allocate() does not throw")
have both been green this whole time. This document was contradicting the contract, the code and two
passing tests at once.

The real rule is about **balance, not repetition**: every `retain()` needs its own `close()`, because
`close()` releases only when the count reaches zero. Hand a buffer to a subtask without retaining
first and the parent's `close()` frees it while the subtask still reads — *that* is the
use-after-free, and on the Enterprise tier a `SIGSEGV`. Write all tests with
`LeakDetectionMode.PARANOID`.

An SPI implementation that is *not* idempotent would be violating the contract, so a caller need not
guard against double close — but a caller that cannot see which implementation it holds may still
choose to, and should say that is why rather than citing a decrement that does not happen.

### Rule 3: `retain()` Before Any `fork()`

```java
buffer.retain();   // refCount +1 BEFORE forking
scope.fork(() -> {
    try {
        return process(buffer);
    } finally {
        buffer.close();  // refCount -1 in subtask
    }
});
```

Forgetting `retain()` means the parent scope may `close()` the buffer before the subtask reads it.

### Rule 4: `Arena.ofConfined()` is Banned in Business Logic

Never open an Arena directly. Use `MemoryAllocator.allocate(AllocationHint)`. Direct Arena usage
bypasses `WatermarkManager` and breaks the Zero-Allocation Covenant.

---

## Debugging Native Calls (Glass-Box JFR Workflow)

Standard JDWP debugger does not step into Panama FFM `downcall` frames (OpenSSL, `io_uring`).
The debugging workflow for native failures:

1. **Enable JFR with Exeris event profile:**
   ```bash
   jcmd <pid> JFR.start name=debug settings=profile filename=debug.jfr
   ```
2. Reproduce the failure.
3. Dump and open in JDK Mission Control:
   ```bash
   jcmd <pid> JFR.stop name=debug
   jmc debug.jfr
   ```
4. In JMC Event Browser → `Exeris / Crypto` → look for `TlsHandshakeFailureEvent`.
   The `nativeErrorCode` field is the OpenSSL error code.
5. Decode the error:
   ```bash
   openssl errstr <nativeErrorCode>
   # Example: openssl errstr 0x1416F086
   # → SSL routines:tls_process_server_certificate:certificate verify failed
   ```
6. For `io_uring` failures: inspect `cqe.res` (negative errno) in carrier loop JFR events.

---

## Compute-Once Config Pattern (Supplier + CAS)

For singleton config caches and expensive one-time initialisations, use `Supplier<T>` combined with
`AtomicReference` CAS — the same pattern modelled in the `ConfigProvider` SPI/TCK.
Do NOT use double-checked locking (`volatile` + `if (field == null)`) — it is banned.

```java
// ✅ CORRECT: SPI-aligned "compute once" cache using Supplier + CAS
private static final AtomicReference<KernelSettings> SETTINGS_REF = new AtomicReference<>();

public static KernelSettings settings() {
    KernelSettings current = SETTINGS_REF.get();
    if (current != null) {
        return current;
    }
    KernelSettings computed = KernelSettings.load();
    return SETTINGS_REF.compareAndExchange(null, computed) == null
            ? computed
            : SETTINGS_REF.get();
}

// ❌ BANNED: manual DCL, volatility, synchronization noise
private static volatile KernelSettings settings;
public static KernelSettings get() {
    if (settings == null) {
        synchronized (KernelSettings.class) {
            if (settings == null) settings = KernelSettings.load();
        }
    }
    return settings;
}
```
