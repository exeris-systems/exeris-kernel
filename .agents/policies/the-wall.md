---
title: Policy — The Wall and the module boundary
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-17
---

# Policy — The Wall and the module boundary

Hard constraints. The Wall is the load-bearing architectural invariant of this repository: SPI
declares contracts, implementations stay behind them, and the dependency direction never inverts.
Changing these rules means changing [ADR-006](../../docs/adr/ADR-006.link.md) and the module
documents in [`docs/modules/`](../../docs/modules), not this file.

## Import rules by module

Reactor modules (root [`pom.xml`](../../pom.xml)) and what each may depend on. Where this table and
the poms disagree, the poms win and this file is the defect.

| Module | Role | May depend on |
|---|---|---|
| `exeris-kernel-spi` | Contracts + carriers ("The Constitution") | **only `java.*` / `jdk.*`** |
| `exeris-kernel-core` | Driver-agnostic orchestration, bootstrap; the HTTP codec and runtime currently live here | SPI. **Never** community or enterprise |
| `exeris-kernel-community` | Open providers (transport, persistence/JDBC, flow, events, security, …) | SPI, plus 15 Core **roots**, sub-packages included: `core.bootstrap`, `core.concurrent`, `core.crypto`, `core.events`, `core.flow`, `core.graph`, `core.http`, `core.memory`, `core.persistence`, `core.scheduling`, `core.security`, `core.storage`, `core.telemetry`, `core.transport`, `core.websocket`. A Core root outside that set is a boundary change and needs an ADR |
| `exeris-kernel-community-kafka` | Kafka/Redpanda event and flow bindings | SPI, community, plus the Core packages it already reaches: `core.events` and `core.telemetry` (three packages: `core.events`, `core.events.outbox`, `core.telemetry.jfr`). A Core root outside that set is a boundary change and needs an ADR, the same rule the Community row carries. This row read "SPI, community" until 2026-09-17, which the reactor has never matched — `exeris-kernel-community-kafka/pom.xml` declares `exeris-kernel-core` in compile scope. A warm-up seam was built in Community to spare this module an import it was already making |
| `exeris-kernel-community-testkit` | Shared test fixtures | — |
| `exeris-kernel-tck` | Contract tests (`Abstract*Tck`) and `ExerisArchitectureTest`, the ArchUnit Wall guard — **SPI reach only**; the Core/Community half is `KernelTierBanArchitectureTest` in Community | SPI |
| `exeris-kernel-diagnostics-cli` | Diagnostics tooling (thin, coverage-ungated) | — |
| `exeris-kernel-bom` / `-parent` / `-build-config` | Build plumbing; build-config ships the lint rulesets and is itself lint-exempt | — |

**The Community row is enumerated, not adjectival, because both adjectives that preceded it were
false.** It read "SPI only. Never core internals" until 2026-09-05, which the reactor has never
matched: `exeris-kernel-community/pom.xml` declares `exeris-kernel-core`. It then read "never Core
orchestration or bootstrap internals", which is false too — Community main sources import
`core.bootstrap.BootstrapProviderSelector`, `core.bootstrap.health.KernelHealthMonitor`,
`core.events.outbox.OutboxOrchestrator` and the `*Bootstrap` entry points of flow, graph, scheduling,
persistence and events.

**Roots, not exact packages, and the row now says so.** The reach is one level finer than the list:
30 distinct Core packages, all of them nesting under the 15 roots — `core.crypto` itself is never
imported, only `core.crypto.openssl` and `core.crypto.tls`, and seven `*.jfr` and driver
sub-packages (`core.http.jfr`, `core.security.jfr`, `core.transport.jfr`, `core.transport.syscall`,
`core.transport.scheduler`, `core.telemetry.jfr`, `core.bootstrap.health`) are reached without being
named. Read as exact packages, the list would make each of those an undeclared boundary change; read
as roots, which is what it has always been, nothing sits outside it.

**Measured on `fix/tck064-jfr-event-class-init-pinning`, 2026-09-17: 115 import statements across 82
distinct types, in 30 packages under those 15 roots.** The figure this replaced — 111 across 79 —
was not made stale by the branch that carried the `last-verified` stamp to 2026-09-17: it was
already stale at that branch's merge base, where the same commands answer 113 and 80. A count in
prose goes out of date on a cadence nothing here enforces, which is why the rule is the root set and
the count is evidence of when it was last taken.

Regenerate both. The exact reach, which is what a review of one import wants:

```bash
grep -rho 'import eu\.exeris\.kernel\.core\.[A-Za-z0-9_.]*;' exeris-kernel-community/src/main/java \
  | sort -u
```

And the roll-up to roots, which is the form comparable to the row — the command above emits 82 lines
that cannot be checked against 15 entries by eye:

```bash
grep -rho 'import eu\.exeris\.kernel\.core\.[A-Za-z0-9_.]*;' exeris-kernel-community/src/main/java \
  | sed 's/^import eu\.exeris\.kernel\.//; s/;$//' \
  | sed 's/^\(core\.[a-z0-9_]*\).*/\1/' | sort -u
```

**The direction is what a guard actually enforces**: `coreDoesNotDependOnCommunity` in
`KernelTierDirectionArchitectureTest` fails a build on Core → Community, and it is not vacuous — the
suite asserts each tier is on its classpath first. See [`scoped-bans.md`](scoped-bans.md) for how to
run it, and note that `-pl exeris-kernel-tck -am` does not.

## Hard constraints

- SPI stays implementation-blind. No driver, native or OS-specific detail enters an SPI contract.
- Core stays driver-agnostic and orchestrates through SPI contracts.
- No framework DI in runtime kernel code — explicit construction and the `ServiceLoader` model.
- No `ThreadLocal` for runtime context propagation; use `ScopedValue`.
- No unstructured concurrency in runtime orchestration paths where a structured scope is expected.
- New SPI surface, or changed observable SPI behaviour, requires executable `Abstract*Tck` coverage
  plus binding tests before merge.
- A new `ExerisKernelException` subclass requires a `rawArgs` layout comment and an error code
  registered in `exeris-kernel-spi/.../spi/exceptions/KernelErrorCodes.java`, the single source of
  truth. No string literals in exception constructors.

## Repository realities

- `exeris-kernel-enterprise` is **not in this repository** — it is a separate closed-source
  distribution. Never deep-link into an enterprise-private repository from a public document; when
  a private ADR needs a public counterpart, write one, do not leave a dead link stub.
- Enterprise-facing claims in these documents describe the shared Core engine (FFM and OpenSSL per
  [ADR-008](../../docs/adr/ADR-008-open-core-strategy-and-commoditization-of-off-heap-tls.md)), not
  code a reader can open here.
- `tools/jfr-reporter` is CI tooling and sits outside the reactor.

## What actually checks this

The ArchUnit guard, not review discipline — and it sees less than its name suggests.
[`scoped-bans.md`](scoped-bans.md) says which suite reaches which tier;
[`definition-of-done.md`](definition-of-done.md) says when to run it.
