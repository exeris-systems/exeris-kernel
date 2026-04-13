# Hub/Hero Asset: Exeris Adoption Path - From Standard Java Stack to Zero-Copy Runtime

## Executive Summary
Exeris adoption is designed as a staged path: start where your team is today, harden contracts, then move high-cost paths toward bounded allocation and zero-copy behavior where it matters most.

This path is optimized for two outcomes:
- FinOps resource efficiency (cost per request, cost per transaction).
- High density (more stable throughput per vCPU under load).

## Stage 0 - Baseline and Cost Map (No Runtime Rewrite)

Primary subsystem focus:
- `telemetry`, `exceptions`, `config`

What to do:
- Establish baseline KPIs: p95/p99 latency, CPU per request, memory per request, error budget.
- Normalize error signals around stable `EX-*` codes and JFR events.
- Freeze operating defaults via explicit config keys and fail-fast expectations.

Exit criteria:
- You can explain current spend and latency using repeatable telemetry.
- Hot-path incidents are visible through code-based error taxonomy, not only logs.

## Stage 1 - Contract First (Boundary Hardening)

Primary subsystem focus:
- `spi`, `core`, `tck`

What to do:
- Move integration seams behind SPI contracts where missing.
- Add or align TCK coverage for observable behaviors you depend on.
- Remove implementation leakage across module boundaries.

Exit criteria:
- Critical paths are expressed through contracts, not concrete runtime classes.
- Regression risk is reduced by executable contract tests.

## Stage 2 - Admission and Runtime Stability (Density Before Speed)

Primary subsystem focus:
- `transport`, `security`, `persistence`, `memory`

What to do:
- Enable and tune admission logic (PAQS and persistence admission checks) to avoid overload collapse.
- Enforce fail-closed security at ingress with clear 401/403 semantics.
- Use bounded pool sizing and shedding thresholds to prioritize critical workloads.

Exit criteria:
- Under synthetic pressure, the system sheds predictably instead of cascading.
- Critical traffic remains serviceable while low-priority traffic is throttled/shed.

## Parallel Future Track - Spring Application Model on Exeris Runtime

Primary focus:
- Separate `exeris-spring-runtime` integration layer (outside this repository)

What to do:
- Evaluate this path when Spring programming model continuity is a hard requirement.
- Keep boundary integrity: no Spring dependency leakage into kernel SPI/Core.
- Choose explicitly between pure Exeris runtime ownership and compatibility-oriented mode.

Exit criteria:
- Team can preserve Spring delivery ergonomics without surrendering runtime ownership assumptions.
- Architecture decision is documented as future-track until officially part of kernel release posture.

## Stage 3 - Low-Allocation Data Plane (Community Path)

Primary subsystem focus:
- `http`, `transport`, `crypto`, `memory`

What to do:
- Keep wire handling on off-heap buffers where supported by current implementation.
- Remove avoidable heap copies in request/response processing.
- Validate zero/low-allocation expectations with JFR and targeted TCK/integration tests.

Exit criteria:
- Request path allocation profile is materially lower than baseline.
- Throughput per vCPU improves without instability regressions.

## Stage 4 - Persistence and Workflow Efficiency

Primary subsystem focus:
- `persistence`, `events`, `flow`, `graph`

What to do:
- Reduce unnecessary object churn in persistence and event paths.
- Keep transactional guarantees and idempotency intact while optimizing throughput.
- Apply workflow and graph capabilities where they remove duplicate application-layer work.

Exit criteria:
- Lower CPU and allocation overhead for DB/event-heavy workloads.
- Clear operational boundaries for retries, DLQ, and long-running flow behavior.

## Stage 5 - Advanced Zero-Copy Trajectory (Enterprise Scope)

Primary subsystem focus:
- Enterprise runtime capabilities (outside this repository)

What to do:
- Evaluate enterprise-only path for kernel-bypass and full end-to-end zero-heap goals.
- Prioritize only if Stage 0-4 metrics show a clear economic case.

Exit criteria:
- Decision is justified by measured savings and density gains, not by architecture fashion.

## Adoption Patterns by Team Maturity

Pattern A - Conservative
- Goal: risk reduction and cost visibility first.
- Typical path: Stage 0 -> 1 -> 2.

Pattern B - Throughput Focused
- Goal: stabilize under load quickly.
- Typical path: Stage 0 -> 2 -> 3.

Pattern C - Platform Team
- Goal: long-term contract governance and multi-service standardization.
- Typical path: Stage 0 -> 1 -> 2 -> 3 -> 4.

Pattern E - Spring Migration Program (Future Track)
- Goal: retain Spring application model while moving runtime ownership toward Exeris.
- Typical path: Stage 0 -> 1 -> 2 + Parallel Future Track -> 3 -> 4.

Pattern D - Extreme Density Program
- Goal: maximum resource efficiency for high-scale workloads.
- Typical path: Stage 0 -> 1 -> 2 -> 3 -> 4 -> 5.

## KPI Ladder (What to Measure at Each Stage)
- Cost per request.
- Requests per vCPU at target latency SLO.
- Allocation rate on request hot path.
- Shed ratio by priority under stress.
- Error-code distribution (`EX-*`) per subsystem.

## Current Repository Reality Notes
- Community runtime path is present in this repository.
- HTTP codec/runtime implementation is currently embedded in Core.
- Enterprise runtime capabilities are out-of-repo and should be treated as separate distribution scope.
- `exeris-spring-runtime` exists as a separate repository, in early architecture/bootstrap stage; treat it as future adoption scope until officially included in kernel release posture.
