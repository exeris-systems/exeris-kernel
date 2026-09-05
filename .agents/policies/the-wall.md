---
title: Policy — The Wall and the module boundary
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
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
| `exeris-kernel-community` | Open providers (transport, persistence/JDBC, flow, events, security, …) | SPI, plus the **published** Core surface it is meant to reuse — `AbstractLoanedBuffer`, the HTTP/1 codec, Core JFR event types. **Never** Core orchestration or bootstrap internals |
| `exeris-kernel-community-kafka` | Kafka/Redpanda event and flow bindings | SPI, community |
| `exeris-kernel-community-testkit` | Shared test fixtures | — |
| `exeris-kernel-tck` | Contract tests (`Abstract*Tck`) and `ExerisArchitectureTest`, the ArchUnit Wall guard | SPI |
| `exeris-kernel-diagnostics-cli` | Diagnostics tooling (thin, coverage-ungated) | — |
| `exeris-kernel-bom` / `-parent` / `-build-config` | Build plumbing; build-config ships the lint rulesets and is itself lint-exempt | — |

**The Community row is stated as measured, not as intended.** It read "SPI only. Never core
internals" until 2026-09-05, and the reactor has never matched that: `exeris-kernel-community/pom.xml`
declares `exeris-kernel-core`, and Community main sources carry 92 `eu.exeris.kernel.core.*` import
statements across 65 distinct types on this branch. The boundary that is real, and the one to
enforce in review, is the one in the table — published Core surface yes, Core orchestration and
bootstrap internals no. Reviewing against the old row produced findings the build had already
refuted.

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
