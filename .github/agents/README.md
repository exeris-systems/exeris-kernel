# Exeris Agents Routing Cheat Sheet

This directory defines a functional multi-agent setup for Exeris Kernel.

## Entry Point
- Use **Exeris Router** for default triage and routing.
- Router classifies task type, highlights constraints, and recommends specialist handoffs.

## Functional Agents
- **Exeris Architect**
  - Placement decisions, ADR alignment, The Wall, boundary breaches.
- **Exeris Implementer**
  - Code delivery and refactoring with Java 26 runtime idioms.
  - Must request TCK/Test review when SPI-observable semantics change.
- **Exeris TCK/Test**
  - Contract verification strategy, TCK expansion, bindings, observable behavior checks.
- **Exeris Performance/Memory**
  - Hot-path allocations, ownership/lifecycle discipline, hidden copies, JFR risk review.
- **Exeris Docs/ADR**
  - Doc drift checks, ADR impact, subsystem docs sync, repository-state clarity.

## Shared Skills (recommended)
- `exeris-architect-guardrails` (architecture guardrails)
- `exeris-performance-contract` (performance contract checks)
- `exeris-docs-adr-check` (docs/ADR consistency)
- `exeris-tck-first` (contract-first verification)
- optional: `exeris-java26-panama-loom` (Java 26 runtime idioms)

## Handoff Contracts
1. **Implementer -> TCK/Test**
   - If implementation changes SPI-observable semantics, mark `TCK review required` before done.
2. **Architect -> Docs/ADR**
   - If architecture intent/placement changes, request docs/ADR consistency pass.
3. **Performance/Memory -> TCK/Test**
   - If runtime behavior changes become contract-observable, require TCK implication review.

## Example Prompts
- "Use Exeris Router to classify this task and pick the right specialist agent."
- "Use Exeris Architect to review module placement and ADR alignment for this PR."
- "Use Exeris Implementer to apply this refactor without changing architecture boundaries."
- "Use Exeris TCK/Test to define required TCK/binding updates for this SPI change."
- "Use Exeris Performance/Memory to review hot path allocation and copy behavior."
- "Use Exeris Docs/ADR to check doc drift and whether ADR update is needed."

## Reusable Prompt Pack
- See `.github/prompts/README.md` for starter slash prompts.
- See `.github/prompts/exeris-community-prompt-pack.md` for the full Community/Open-Core prompt catalog.
