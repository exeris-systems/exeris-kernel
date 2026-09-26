---
title: "exeris-kernel: the open side of the Exeris runtime kernel"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
---

# exeris-kernel

Guardrails for AI assistants working inside this repository — the contract a session must respect,
and an index to where each rule lives. Human onboarding is [`README.md`](README.md) and
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Mission and scope

Exeris Kernel is a cloud-native, zero-copy runtime platform for the JVM — the open side of an
open-core product. It replaces framework-heavy Java stacks with a system-level kernel: Panama FFM
off-heap memory, virtual threads, `ScopedValue` context, JFR-first observability, OpenSSL through
FFM. **It is not a standard Java application, and standard Java idioms are often wrong here.**

Two ideas carry the design and everything defers to them: **No Waste Compute** — every byte and
cycle must add value — and **The Wall**, the SPI/implementation separation.
[`docs/glossary.md`](docs/glossary.md) is authoritative for both.

Coordinates: groupId `eu.exeris`, packages `eu.exeris.kernel.<module>.<subsystem>`. Every subsystem
has a contract document in [`docs/subsystems/`](docs/subsystems), and it outranks this file on that
subsystem's behaviour. The active development base is written down nowhere here: resolve it from the
remote with `git branch -r --list 'origin/development/*' | sort -V | tail -1`, never from a version
in a file ([branching](.agents/policies/branch-and-release.md)).

## Operating contract

**There are two distribution tracks, and a statement true on one can be disqualifying on the
other.** This branch is the distributable line: LTS JDK, preview-clean main sources. The `preview`
branch takes the newest JDK with `--enable-preview` and keeps `StructuredTaskScope`. Which line you
are on decides what a concurrency mandate means —
[the JDK track](.agents/policies/jdk-and-preview-track.md).

**Non-negotiable, whatever the task:**

- The Wall holds. SPI stays implementation-blind, Core stays driver-agnostic, and the dependency
  direction never inverts.
- No `ThreadLocal` for context propagation, no framework DI in runtime code, no `sun.misc.Unsafe`.
- Native memory has an explicit owner and a deterministic lifecycle.
- New or changed observable SPI behaviour does not merge without executable TCK coverage.
- Classify the scope before applying a ban or judging a diff: hot path, non-hot, test-tooling,
  docs-only.
- Never invent target-state. A missing or stale document is reported as missing; the fallback is the
  source layout.
- Never deep-link a public document into an enterprise-private repository.

Each is stated once, with its reasoning and its exceptions, under `.agents/policies/`.

## Architecture and documentation entry points

Smallest sufficient authoritative set, in this order:

1. [`docs/modules/`](docs/modules) and [`docs/subsystems/`](docs/subsystems) — placement, behaviour.
2. [`docs/adr/`](docs/adr) when a boundary, the lifecycle model or the module split is affected;
   [`docs/rfc/`](docs/rfc) for designs in flight.
3. [`whitepaper`](docs/whitepaper.md), [`architecture`](docs/architecture.md) and the
   [`performance contract`](docs/performance-contract.md) — philosophy and numeric SLOs.
4. [`docs/ROADMAP.md`](docs/ROADMAP.md) for milestone intent and 1.0 GA constraints;
   [`CONTRIBUTING.md`](CONTRIBUTING.md) for build, off-heap and debugging mechanics.

ADR and RFC numbers are a **global namespace across the Exeris ecosystem**: reserve the number in
the registry before writing content.

## `.agents/` — the canonical semantic source

Detailed rules are authored once, under [`.agents/`](.agents), and nowhere else. This file indexes
and bounds them.

| Path | What it holds |
|:--|:--|
| [`.agents/policies/`](.agents/policies) | What is permitted or forbidden — the Wall, scoped bans, memory ownership, the JDK track, definition of done, operating standards, branching, the SonarQube MCP server. Two more arrive from the bundle as `bundle:<name>`. |
| [`.agents/references/`](.agents/references) | The short form of facts owned elsewhere: `build-and-ci`, `testing-model`. Each names its source and yields to it. |
| [`.agents/skills/`](.agents/skills) | Bounded capabilities: the PR-review and subsystem lenses, single-pass triage, preflight, ADR registration, JFR research, release integration, tagged gates. |
| [`.agents/agents/`](.agents/agents) | Role profiles at `<name>/AGENT.md`, composed from those skills — router, architect, implementer, TCK, performance, docs, evaluator. Vendor-neutral: they declare capabilities and a model tier, not a runtime's tool names. |
| [`.agents/workflows/`](.agents/workflows) | User-invoked review sequences, each declaring its steps and the gates that enforce them. |
| [`.agents/schemas/`](.agents/schemas) | The shape of a decision handed between roles: triage, verdict, handoff. |
| [`.agents/hooks/`](.agents/hooks) | The L0 layer: what is denied, and what is allowed with a consequence the stop gate then requires. It enforces rules written elsewhere and states none. |
| [`.agents/evals/`](.agents/evals) | Behaviour tests for the profiles. On demand, not per pull request. |
| [`.agents/vendor/`](.agents/vendor) | The pinned, digest-verified copy of the shared bundle. Never edited. |
| [`.agents/manifest.yaml`](.agents/manifest.yaml) | The composition, the pinned import, the render map, and each runtime's limits. |

Four subtrees add their own `AGENTS.md`, each naming what enforces it and restricting only:
[spi](exeris-kernel-spi/AGENTS.md), [core](exeris-kernel-core/AGENTS.md),
[community](exeris-kernel-community/AGENTS.md), [tck](exeris-kernel-tck/AGENTS.md).

Instruction sources resolve broad to narrow: organisation bundle, repository, subtree, selected
workflow. A narrower file may restrict behaviour; it may never relax a higher-order rule. Accepted
ADRs, the subsystem contracts and the ecosystem standards outrank anything summarised here — where
they disagree, this file is the defect.

**Pick a surface by how the work should run, not by what it is.** A *skill* runs inline and is the
default — start any review with `exeris-pr-review-waste-hunter`. An *agent* gets its own context
window, for read-heavy fan-out. A *workflow* is user-invoked as a slash command.

## Verification and reporting

`mvn clean install` is the only build command that counts here, and with no skip flags it is
lint-gated. **A green build proves only what it ran:** it excludes the `integration`, `continuity`
and `stress` gates, and a `-Dpmd.skip` anywhere in the loop voids its lint evidence. What may not be
skipped: [definition of done](.agents/policies/definition-of-done.md). Commands:
[build and CI](.agents/references/build-and-ci.md).

Report the outcome first. A claim names the command that proves it, verified against the effective
source — poms, workflows, rulesets — never against an agent file. Say what you did not verify as
plainly as what you did.

## Conventions and contribution terms

The binding standards live in
[`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards) — commit
and PR conventions, the docs style guide, ADR conventions, `ai-provenance.md`, and the
`agents-md-schema.md` this file answers to. Where this file and a standard disagree, the standard
wins and this file is the defect.

An AI-assisted commit keeps its `Co-authored-by:` trailer, a named human is accountable for every
line, and an agent does not open pull requests or file issues on its own. Contribution terms:
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Provider adapters

[`.claude/`](.claude) holds Claude Code adapters generated from `.agents/`, each carrying a
do-not-edit marker naming its source, plus provider-owned configuration. **This repository carries
no renderer**: one implementation, shared, lives in `exeris-systems/exeris-agents` and is pinned —
mechanics in [`.claude/README.md`](.claude/README.md). Never edit an adapter; an adapter that
differs from its source fails CI. `CLAUDE.md` points here.
