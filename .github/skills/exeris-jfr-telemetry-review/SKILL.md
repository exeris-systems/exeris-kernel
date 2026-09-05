---
# DO NOT EDIT — generated from .agents/skills/exeris-jfr-telemetry-review/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-jfr-telemetry-review
description: 'Observability contract review for Exeris Kernel. Use for PRs touching bootstrap, telemetry, memory, transport, lifecycle/state machines, and exception mapping to enforce JFR-first events, lightweight emission, secret-safe payloads, L0/L1 boundaries, and error/rawArgs contracts.'
argument-hint: 'PR scope, changed lifecycle points, and telemetry/error-code impact'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-jfr-telemetry-review/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris JFR Telemetry Review

## Purpose
Enforce observability as a contract, not a best-effort logging add-on.

This skill validates that lifecycle-critical behavior emits compliant telemetry with minimal overhead and safe payload semantics.

## Canon to Load First
- docs/subsystems/telemetry.md
- docs/subsystems/exceptions.md
- docs/performance-contract.md
- docs/architecture.md
- docs/whitepaper.md
- docs/modules/02-core.md
- docs/modules/05-tck.md
- related subsystem docs for changed code paths (bootstrap/memory/transport/flow/config/security)

## When to Use
- PR touches bootstrap or subsystem lifecycle orchestration
- PR changes memory allocation paths or allocation-failure handling
- PR changes transport bind/start/handshake/state transitions
- PR changes telemetry sink/event mapping, error codes, or exception payloads (`rawArgs`)
- PR modifies L0/L1 interaction boundaries for observability

## Required Inputs
- PR diff or changed file list
- Changed lifecycle transitions and state-machine edges
- Changed exception/error-code paths and `rawArgs` schema

## Mandatory Checks
1. **Critical event coverage**
   - Verify telemetry events exist for: bootstrap lifecycle, allocation failure, transport bind/engine start, and state transitions.
   - Flag silent transitions (state changes without typed event emission).

2. **Event weight discipline**
   - Verify JFR events are lightweight; require `@StackTrace(false)` where contract expects zero-overhead emission.
   - Flag payload inflation or heavy per-event formatting/serialization on emission path.

3. **Secret leakage guard**
   - Verify no credentials/tokens/secrets are emitted in `rawArgs` or event fields.
   - Require caller-side redaction/truncation for sensitive values before emission.

4. **L0/L1 boundary integrity**
   - Verify observability responsibilities remain aligned with architecture tiers.
   - Flag boundary violations where telemetry logic leaks into wrong tier or bypasses defined sinks/contracts.

5. **Error-code and rawArgs contract integrity**
   - Verify failures map to stable `EX-*` codes.
   - Verify `rawArgs` layout matches contract semantics (shape/order/meaning) and remains decoder-safe.
   - Flag ad-hoc message-only error handling without contract code/payload.

## Review Procedure
1. **Map telemetry-relevant deltas**
   - Enumerate all lifecycle transitions and failure edges touched by the PR.

2. **Build event matrix**
   - For each transition/failure edge, map: trigger → expected typed event → required fields → tier owner.

3. **Validate emission quality**
   - Check lightweight event annotations and avoid heavy runtime formatting on emission path.

4. **Validate payload safety and semantics**
   - Check secret redaction contract and `rawArgs` schema consistency with error-code intent.

5. **Validate tier boundaries**
   - Confirm L0/L1 responsibilities are preserved and no observability layer inversion occurred.

6. **Gate outcome**
   - Output `APPROVE`, `CONDITIONAL`, or `REJECT` with minimal root-cause fixes.

## Decision Logic
- **APPROVE**: Required lifecycle events are present, lightweight, safe, and contract-correct; boundaries preserved.
- **CONDITIONAL**: Minor telemetry gaps or payload hygiene issues with clear bounded remediation.
- **REJECT**: Missing critical events, secret leakage risk, boundary violation, or broken error/rawArgs contract.

## Completion Criteria
Review is complete only if all are true:
- Event coverage validated for bootstrap, allocation failure, bind/start, and state transitions.
- Event overhead reviewed (`@StackTrace(false)` and emission-path weight where applicable).
- Secret leakage review completed for event fields and `rawArgs`.
- L0/L1 boundary checks completed.
- Error-code and `rawArgs` contract checks completed.
- Verdict and required fixes documented.

## Review Output Template
1. Scope analyzed (files, transitions, failure edges)
2. Event coverage findings
3. Event weight findings
4. Secret hygiene findings
5. L0/L1 boundary findings
6. Error/rawArgs contract findings
7. Verdict (APPROVE | CONDITIONAL | REJECT)
8. Required actions (minimal root-cause fixes)

## Non-Negotiable Rules
- No critical lifecycle transition without typed telemetry event.
- No secret-bearing payload in events or `rawArgs`.
- No contract drift between error code and `rawArgs` schema.
- No tier-boundary violations in telemetry responsibilities.
