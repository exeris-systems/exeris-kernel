---
name: exeris-subsystem-specialist
description: Targeted single-subsystem review for Exeris Kernel via one mode-driven skill — one mode per contract doc in docs/subsystems/ (bootstrap, config, crypto, events, exceptions, flow, graph, http, memory, persistence, security, telemetry, transport). Use when a change is concentrated in one subsystem and you want its contract doc loaded as the primary source of truth plus mode-specific deep checks — without spawning multiple agents. State the mode (e.g. "review this as Transport").
---

# Exeris Subsystem Specialist

## Purpose
Provide one lightweight, mode-driven subsystem review skill instead of many dedicated agents.

This skill keeps one shared Exeris philosophy while switching to subsystem-specific checks via mode.

## Supported Modes
- bootstrap
- memory
- crypto
- transport
- persistence
- events
- flow
- http
- security
- config
- exceptions
- graph
- telemetry

## Invocation Pattern
Use a mode-first subprompt, for example:
- Review this as Transport subsystem
- Review this as Memory subsystem
- Review this as Security subsystem

If mode is not explicit, infer from changed files. If still ambiguous, ask for one mode before continuing.

## Canon to Load Before Review
Always load:
- docs/architecture.md
- docs/whitepaper.md
- docs/performance-contract.md
- docs/modules/01-spi.md
- docs/modules/02-core.md
- docs/modules/03-community.md
- docs/modules/04-enterprise.md
- docs/modules/05-tck.md
- related ADRs in docs/adr

Then load exactly one subsystem contract based on selected mode:
- bootstrap → docs/subsystems/bootstrap.md
- memory → docs/subsystems/memory.md
- crypto → docs/subsystems/crypto.md
- transport → docs/subsystems/transport.md
- persistence → docs/subsystems/persistence.md
- events → docs/subsystems/events.md
- flow → docs/subsystems/flow.md
- http → docs/subsystems/http.md
- security → docs/subsystems/security.md
- config → docs/subsystems/config.md
- exceptions → docs/subsystems/exceptions.md
- graph → docs/subsystems/graph.md
- telemetry → docs/subsystems/telemetry.md

## Mode Workflow
1. **Select mode**
   - Explicit mode from user wins.
   - Otherwise infer from changed symbols/files.
   - If multiple modes compete, choose primary mode and list secondary impacts.

2. **Build review lens for selected mode**
   - Extract contract invariants, lifecycle semantics, performance limits, and error model from subsystem doc.
   - Extract cross-module boundaries from module docs and ADRs.

3. **Run focused checks**
   - Contract correctness: changed behavior must match subsystem contract semantics.
   - Boundary integrity: no SPI leakage, no Core driver leakage, no The Wall breach.
   - Performance law: no regressions against No Waste Compute constraints.
   - TCK implications: contract changes require executable TCK impact analysis and bindings.

4. **Run mode-specific deep checks**
   - bootstrap: startup lifecycle ordering, bootstrap events, readiness/latency gates.
   - memory: allocator ownership, leak safety, ref-count discipline, O(1) alloc-release behavior.
   - crypto: handshake lifecycle, zero-alloc constraints, native boundary hygiene, error-code contracts.
   - transport: connection/stream lifecycle, backpressure/load-shed semantics, zero-copy data path.
   - persistence: transaction boundaries, rollback semantics, concurrency invariants, consistency contract.
   - events: delivery semantics, ordering/idempotency expectations, provider-switching contract behavior.
   - flow: scheduling and park-wake semantics, latency/throughput invariants, state-machine correctness.
   - http: provider/server/client/handler/exchange contract conformance at SPI boundary.
   - security: context propagation, identity/authorization invariants, isolation and auditability guarantees.
   - config: schema and validation semantics, deterministic resolution/override precedence, boot-time safety.
   - exceptions: `KernelErrorCodes` registry integrity (single source of truth, no string literals in constructors), `rawArgs[]` layout comments and primitive-only payloads, no `String.formatted()`/concat on failure paths, checked-exception ban on hot state machines.
   - graph: traversal/query contract semantics, zero-copy payload handoff at persistence boundaries, deterministic lookup complexity on hot paths.
   - telemetry: JFR-first event contracts, single-phase `commit()` (never begin → blocking-op → commit on a virtual thread), `@StackTrace(false)` weight discipline, secret-safe payloads, L0/L1 sink boundaries.

5. **Decision and report**
   - Output verdict: APPROVE, CONDITIONAL, REJECT.
   - Map findings to mode contract clause, impacted symbols, runtime risk, and minimal corrective action.

## Decision Logic
- APPROVE: selected mode contract is fully preserved and no cross-cutting guardrail is violated.
- CONDITIONAL: localized drift with clear remediation and no fundamental contract break.
- REJECT: subsystem contract violation, boundary breach, performance law breach, or missing TCK implications for contract changes.

## Completion Criteria
A review is complete only if all are true:
- Mode selection is explicit and justified.
- Selected subsystem doc was loaded and used as primary contract source.
- Cross-cutting checks were run (architecture, performance, TCK).
- Mode-specific deep checks were run and evidenced.
- Verdict and required actions are precise and minimal.

## Review Output Template
1. Scope analyzed (mode, files, symbols)
2. Contract findings (selected subsystem)
3. Boundary findings (SPI/Core/The Wall)
4. Performance findings (No Waste Compute)
5. TCK findings (contract test implications)
6. Verdict (APPROVE | CONDITIONAL | REJECT)
7. Required actions (minimal root-cause fixes)

## Non-Negotiable Rules
- One review, one primary mode, one contract source of truth.
- No subsystem review without loading its subsystem doc.
- No contract-changing PR accepted without TCK implications analysis.
- No violation of architecture boundaries or performance contract under any mode.
