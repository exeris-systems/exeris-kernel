---
name: exeris-tck-first
description: TCK-first review for Exeris Kernel. Use when a change touches SPI interfaces, value/error contracts, lifecycle/state behavior, or provider bindings — to enforce that every contract change has executable Abstract*Tck coverage plus binding tests in Core/Community/Enterprise, with semantic (not happy-path-only) assertions. Trigger on new contract surface, changed error codes, or altered observable behavior.
---

# Exeris TCK First

## Purpose
Enforce **TCK as The Judge**: every contract change must have test implications before merge.

This skill treats `exeris-kernel-tck` as merge gate for contract validity, not optional test coverage.

## Canon to Load First
- `docs/modules/05-tck.md`
- `docs/modules/01-spi.md`
- `docs/modules/02-core.md`
- `docs/modules/03-community.md`
- `docs/modules/04-enterprise.md`
- impacted subsystem contracts in `docs/subsystems/*.md`
- related ADR constraints in `docs/adr/*.md`

## When to Use
- Any PR modifying SPI interfaces, value contracts, error semantics, lifecycle behavior, or provider discovery
- Any PR changing observable behavior in Core/Community/Enterprise that should remain contract-consistent
- Any PR adding new contract surface, new provider type, or altering contract invariants

## Required Inputs
- PR diff or changed file list
- Changed contract symbols (interfaces/methods/error codes/lifecycle states)
- Expected observable behavior delta

## Mandatory Review Questions
For each contract-impacting change, answer explicitly:
1. **SPI → Abstract TCK:** Does this SPI change require a new or updated `Abstract*Tck` suite?
2. **Bindings:** Do Core and Community/Enterprise need new or updated binding tests to execute that abstract suite?
3. **Observable Behavior:** Is the behavior defined at contract level (not implementation detail), with assertions proving it?
4. **Test Depth:** Do tests validate zero-alloc / ref-count / leak / contract semantics, not only happy path?

## TCK-First Procedure
1. **Detect contract impact**
   - Identify whether changed code affects SPI-level semantics or externally observable lifecycle/state behavior.
   - Classify as: `NO_CONTRACT_CHANGE`, `CONTRACT_EXTENSION`, `CONTRACT_BREAKING_CHANGE`.

2. **Map change to executable contract**
   - For each affected SPI contract, verify corresponding `Abstract*Tck` exists and is updated.
   - If a new SPI contract appears, require a new `Abstract*Tck` in `exeris-kernel-tck` before merge.

3. **Verify test-jar binding coverage**
   - Confirm changed/added abstract suites are executed by binding tests in relevant consuming modules (Core and/or Community/Enterprise test scope).
   - Ensure binding tests assert contract behavior, not fixture internals.

4. **Enforce semantics-over-happy-path**
   - Ensure assertions cover invariants, error contracts, lifecycle transitions, and edge cases.
   - Flag coverage that only proves successful nominal flow.

5. **Enforce runtime integrity checks where relevant**
   - Require zero-allocation checks for hot-path contracts where specified.
   - Require ref-count correctness and leak detection (`PARANOID` mode) for off-heap flows.
   - Require contract-level error semantics (stable error codes/states), not ad-hoc exception text checks.

6. **Gate mergeability**
   - If contract changed but TCK was not added/updated and bound, mark PR **not mergeable**.
   - If tests exist but do not prove contract semantics, mark **conditional** with mandatory remediation.

7. **Decision and report**
   - Output: `APPROVE`, `CONDITIONAL`, or `REJECT`.
   - For each finding: contract symbol → missing/weak TCK evidence → risk → minimal fix.

## Decision Logic
- **APPROVE**: Contract delta is fully represented in abstract TCK + bindings, with semantic and runtime integrity checks where applicable.
- **CONDITIONAL**: TCK intent is present but incomplete (missing edge semantics, weak assertions, or partial bindings).
- **REJECT**: Contract changed without required abstract TCK and bindings; or tests are happy-path-only and do not validate contract semantics/invariants.

## Completion Criteria
Review is complete only if all are true:
- Contract-impact classification is documented.
- `Abstract*Tck` coverage decision is explicit per changed SPI contract.
- Binding coverage decision is explicit for Core and relevant implementations.
- Observable behavior assertions are contract-level and evidence-based.
- Zero-alloc/ref-count/leak/semantic checks are covered where applicable.
- Mergeability verdict is explicit and justified.

## Review Output Template
1. **Scope analyzed** (contracts/modules/subsystems)
2. **Contract impact class** (`NO_CONTRACT_CHANGE` / `CONTRACT_EXTENSION` / `CONTRACT_BREAKING_CHANGE`)
3. **Abstract TCK findings**
4. **Binding test findings** (Core/Community/Enterprise)
5. **Semantics findings** (observable behavior vs happy path)
6. **Runtime integrity findings** (zero-alloc, ref-count, leak)
7. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
8. **Required actions** (minimal merge-blocking fixes)

## Non-Negotiable Rules
- No SPI contract change without corresponding executable TCK implications.
- No merge if abstract TCK and bindings are missing for changed contract semantics.
- No acceptance of happy-path-only tests for contract changes.
- TCK remains verification-only and SPI-oriented; no implementation leakage into abstract suites.
