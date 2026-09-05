---
title: "exeris-kernel: the open side of the Exeris runtime kernel"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
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
[`docs/glossary.md`](docs/glossary.md) is authoritative for both, and for *software inflation* and
*Glass-Box*.

Coordinates: groupId `eu.exeris.preview` **on this branch** (`eu.exeris` on the distributed line),
packages `eu.exeris.kernel.<module>.<subsystem>`. Every subsystem
has a contract document in [`docs/subsystems/`](docs/subsystems), and it outranks this file on that
subsystem's behaviour. The active development base is written down nowhere in this repository: resolve it from the remote
with `git branch -r --list 'origin/development/*' | sort -V | tail -1`, never from a version in a
file ([branching](.agents/policies/branch-and-release.md)).

## Operating contract

**You are on `preview`, and a statement true on the distributed line can be disqualifying here.**
This branch takes the newest JDK — 28 EA today — with `--enable-preview` on main sources as well as
tests, and keeps `StructuredTaskScope`. [`PREVIEW-TRACK.md`](PREVIEW-TRACK.md) is this line's
identity document; [the JDK track](.agents/policies/jdk-and-preview-track.md) is the rule.

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

Each of these is stated once, with its reasoning and its exceptions, under `.agents/policies/`.

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
them and bounds them; it does not restate them.

| Path | What it holds |
|:--|:--|
| [`.agents/policies/`](.agents/policies) | What is permitted or forbidden: [the Wall](.agents/policies/the-wall.md), [scoped bans](.agents/policies/scoped-bans.md), [memory ownership](.agents/policies/memory-ownership.md), [the JDK track](.agents/policies/jdk-and-preview-track.md), [definition of done](.agents/policies/definition-of-done.md), [operating standards](.agents/policies/operating-standards.md), [branching](.agents/policies/branch-and-release.md), [the SonarQube MCP server](.agents/policies/sonarqube-mcp.md). |
| [`.agents/references/`](.agents/references) | The short form of facts owned elsewhere — [build and CI](.agents/references/build-and-ci.md), [the testing model](.agents/references/testing-model.md). Each names its source and yields to it. |
| [`.agents/skills/`](.agents/skills) | Bounded capabilities: PR-review and subsystem lenses, single-pass triage, and the workflow skills — preflight, ADR registration, JFR research, release integration, tagged gates. |
| [`.agents/agents/`](.agents/agents) | Role profiles composed from those skills — router, architect, implementer, TCK, performance, docs. |
| [`.agents/workflows/`](.agents/workflows) | User-invoked Community / Open-Core review sequences. |
| [`.agents/manifest.yaml`](.agents/manifest.yaml) | The composition, and the version-pinned bundles this repository imports. It imports none. |

Instruction sources resolve broad to narrow: organisation bundle, repository, subtree, selected
workflow. A narrower file may restrict behaviour; it may never relax a higher-order rule. Accepted
ADRs, the subsystem contracts and the ecosystem standards outrank anything summarised here — where
they disagree, this file is the defect.

**Pick a surface by how the work should run, not by what it is.** A *skill* runs inline and is the
default — start any review with `exeris-pr-review-waste-hunter`, which dispatches to the focused
lenses. An *agent* gets its own context window, for read-heavy fan-out. A *workflow* is invoked by
the user as a slash command. Skills and agents mirror the same personas on purpose.

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
and PR conventions, the docs style guide, ADR conventions, the
[agent-file schema](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
this file answers to, and
[AI provenance](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).
They are not restated here; where this file and a standard disagree, the standard wins and this file
is the defect.

An AI-assisted commit keeps its `Co-authored-by:` trailer, a named human is accountable for every
line, and an agent does not open pull requests or file issues on its own. Contribution terms:
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Provider adapters

[`.claude/`](.claude) holds Claude Code adapters generated from `.agents/`, each carrying a
do-not-edit marker naming its source, plus provider-owned configuration. Rewrite them with
`tools/agent-adapter-check/agent-adapter-render.sh` and verify with `…/agent-adapter-check.sh`;
never edit an adapter. `CLAUDE.md` points here.
