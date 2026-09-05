---
# DO NOT EDIT — generated from .agents/skills/exeris-tagged-gate-runner/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-tagged-gate-runner
description: 'Run the tagged test gates (integration / continuity / stress) that the default build EXCLUDES, mapped from what a change touched. Use before claiming definition-of-done when touched code is covered by @Tag''d tests, or to reproduce a CI gate (persistence RLS, Kafka, recovery continuity, transport stress) locally.'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-tagged-gate-runner/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Tagged Gate Runner

## Purpose
`mvn clean install` runs **none** of the tagged suites — a green default build is silent about exactly the tests that guard persistence RLS, Kafka delivery, recovery continuity, and transport under load. This skill maps a diff to the gates it must pass and gives the exact local commands (mirroring `.github/workflows/maven.yml`).

## When to Use
- Touched code is covered by `@Tag("integration")` / `@Tag("continuity")` / `@Tag("stress")` tests (definition-of-done step 4).
- Reproducing a red CI gate locally.
- Deciding which gates a change must pass before merge.

## Gate map (verified against maven.yml and @Tag usage)

| Touched area | Tag | Module to run | CI gate |
|---|---|---|---|
| persistence / JDBC / RLS interceptors | `integration` | `exeris-kernel-community` | Persistence RLS/Interceptor Gate |
| events / flow via Kafka or Redpanda | `integration` | `exeris-kernel-community-kafka` | Kafka Integration Gate |
| recovery / continuity (flow & events state) | `continuity` | `exeris-kernel-community` and `exeris-kernel-community-kafka` | Recovery Continuity Gate |
| transport under load / backpressure / load-shed | `stress` | `exeris-kernel-community` | Transport Stress Gate |
| crypto / TLS (OpenSSL FFM) | — (matrix) | Linux-only | TLS OpenSSL 3.x/4.x matrix |

## Steps

1. **Confirm coverage.** For each touched module: `git grep -l '@Tag("<tag>")' -- '<module>/src/test'`. If a touched area appears in the map above, its gate is required.

2. **Preconditions.** Docker running (Testcontainers pulls Postgres/Kafka); JDK 28 EA with `--enable-preview` active, as this line requires. Tagged runs are slower than the default suite — expect minutes, not seconds.

3. **Run each required gate exactly as CI does** (install deps first, then the tagged run; the empty `-DexcludedGroups=` is REQUIRED — without it Surefire keeps the default exclusion and silently runs nothing):

   ```bash
   mvn -pl <module> -am install -DskipTests -B -e
   mvn -pl <module> -DincludedGroups=<tag> -DexcludedGroups= test
   ```

4. **Windows caveat.** FFM/OpenSSL TLS tests auto-skip on Windows — never claim TLS verification from a Windows run; mark that gate DEFERRED-TO-CI explicitly.

5. **Report per gate:** `RUN+GREEN` / `RUN+FAILED` (with the failing output) / `DEFERRED-TO-CI` (with reason). "Deferred" is an honest status; an unstated deferral is a false green.

## Output
The gate table filtered to this change, each row with its status and the exact command used.

## Non-Negotiable Rules
- Never claim a tagged gate passed without having run it (zero tests executed = not a pass — check the test count in the output).
- Never omit the empty `-DexcludedGroups=` override.
- Never claim TLS verification from Windows.
- No merge-gating benchmarks — perf claims go through `exeris-jfr-perf-research`.
