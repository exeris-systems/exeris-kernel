---
title: Reference — build commands and the CI gates
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Reference — build commands and the CI gates

Authoritative sources: [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for mechanics, the root
[`pom.xml`](../../pom.xml) for what is actually bound to which phase, and
[`.github/workflows/maven.yml`](../../.github/workflows/maven.yml) for what CI runs. Where this file
and one of those disagree, the source wins and this file is the defect. What may not be skipped is
[`../policies/definition-of-done.md`](../policies/definition-of-done.md).

## The golden command

```bash
mvn clean install
```

The only one that counts. `compile` proves nothing here. With no skip flags it is lint-gated:
`checkstyle:check` is bound to `validate` and `pmd:check` to `verify`, both failing on violation.

## Standalone lint re-check

Scoped to the changed modules. Never put `exeris-kernel-build-config` in the `-pl` list — it is
parented to `exeris-kernel-root`, so it has no lint bindings and carries `pmd.skip=true`:

```bash
mvn -pl <changed-modules> pmd:check checkstyle:check
```

Run it on a tree that has already been built; see the definition-of-done policy for why a fresh
worktree reports violations that are not there.

## Architecture guard

**On this line the standalone invocation fails, and it is not a boundary breach.** It reports
`[No Class Loaded]` — the guard's own non-vacuity assertion, saying it scanned nothing — because the
isolated `-pl exeris-kernel-tck -am` invocation does not set this line's classpath up. Verified
against a clean `origin/preview` checkout, where it fails identically. The guard itself runs and
passes inside the full `mvn clean install`, which is what CI does, so **use the full build to
satisfy the guard here** and do not read the isolated failure as a finding
([`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md) records this).

```bash
mvn -q -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpmd.skip=true -Dcheckstyle.skip=true test
```

`-Dtest` activates the `targeted-test-run` profile, which unmasks tagged gates; when you need to
exclude rather than select, use `-Dsurefire.excludesFile`.

## Tagged gates the default build excludes

`@Tag("integration")` (Testcontainers Postgres and Kafka), `@Tag("continuity")` and `@Tag("stress")`
do **not** run in `mvn clean install`. Run the ones covering code you touched:

```bash
mvn -pl <module> -DincludedGroups=<tag> -DexcludedGroups= test
```

The `exeris-tagged-gate-runner` skill maps a change to the gates it owes.

## What CI runs

`maven.yml` builds with `mvn clean verify -P coverage` (JaCoCo line and branch floors are ratcheted
per module — do not lower a floor to make a build pass), then a sequenced chain of gates:

`build-and-verify` → `persistence-rls-gate` → `kafka-integration-gate` →
`recovery-continuity-gate` → `transport-stress-gate`, with `spi-compatibility-gate` and
`tls-openssl-matrix` branching off the build, and `benchmarks` plus the JFR reporting jobs on `main`
only. `spi-compatibility-gate` deliberately does not depend on the build: it compiles the SPI alone.

Other workflows: `codeql.yml`, `dependency-review.yml`, `release.yml`, and the two Claude workflows
(`claude.yml` responds only to human `@claude` mentions; `claude-code-review.yml` reviews pull
requests).

## Platform caveats

On Windows the FFM and OpenSSL TLS tests auto-skip. Full native coverage needs Linux with
`libssl.so.3` on the path — never claim TLS verification from a Windows run.
