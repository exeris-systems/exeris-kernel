# Help Asset: Who Exeris Is For - And Who It Is Not For

## Positioning in One Line
Exeris is for teams that treat compute and memory as a business budget, not as an invisible tax.

## Who Exeris Is For

### 1) Teams with FinOps pressure on throughput per core
You are a strong fit if you care about:
- Higher requests per vCPU and lower memory waste.
- Predictable behavior under load (admission control and shedding).
- Cost-per-request as an engineering KPI.

Why this maps to Exeris:
- `memory` + `transport` + `http` + `crypto` are built around off-heap, bounded-allocation paths.
- `telemetry` is JFR-first with explicit error contracts (`EX-*`) and low-overhead event paths.
- `config` and `bootstrap` support fail-fast operation and deterministic startup behavior.

### 2) Teams running high-density multi-tenant workloads
You are a strong fit if you need:
- Isolation by contract, not by convention.
- Stable latency under contention.
- Hard control over noisy-neighbor effects.

Why this maps to Exeris:
- `security` uses `ScopedValue` propagation and fail-closed admission at the edge.
- `persistence` has admission signaling (`canServiceRequest()`) and context-driven isolation.
- `transport` PAQS shedding protects critical traffic before heap pressure cascades.

### 3) Teams that want incremental adoption, not a big-bang rewrite
You are a strong fit if you need to adopt in phases:
- Start with existing JDBC and standard Java app structure.
- Move selected paths to lower allocation and zero-copy behavior over time.
- Keep a contract-tested surface while evolving internals.

Why this maps to Exeris:
- SPI/Core/Community split keeps boundaries explicit.
- TCK layer verifies observable contracts as implementations evolve.
- Community runtime is available in-repo; Enterprise remains separate distribution.

### 4) Teams that value operational truth over framework abstraction
You are a strong fit if you prefer:
- Typed subsystem contracts.
- Explicit lifecycle and ownership rules.
- Diagnostics grounded in stable error code schemas.

Why this maps to Exeris:
- `exceptions` + `telemetry` define deterministic rawArgs schemas per error code.
- `bootstrap` lifecycle and subsystem phases are explicit and documented.

### 5) Teams that want Spring apps with Exeris runtime ownership (Future Track)
You are a potential fit if you need:
- Spring application ergonomics with Exeris execution ownership.
- A migration path that keeps kernel boundaries intact (no Spring in SPI/Core).
- Explicit trade-offs between pure performance mode and compatibility mode.

Why this maps to Exeris:
- `exeris-spring-runtime` is being built as a separate integration layer.
- Its stated direction is Exeris-owned ingress/runtime while preserving The Wall.
- It is suitable as a planned adoption track, not as current in-repo kernel baseline.

## Who Exeris Is Not For

### 1) Teams optimizing for framework convenience first
Not a fit if your top priority is:
- Maximum framework magic and auto-configuration.
- Heavy reflection-driven stacks as the default runtime model.

Reason:
- Exeris favors explicit contracts, explicit bootstrap behavior, and low-level ownership control.

### 2) Teams with no resource-efficiency problem to solve
Not a fit if:
- Your workload is low-volume and low-cost.
- Latency/cost density is not a business concern.

Reason:
- Exeris architecture pays off most when efficiency and predictability are first-order constraints.

### 3) Teams unwilling to enforce subsystem boundaries
Not a fit if:
- You regularly blur contract boundaries across runtime layers.
- You prefer ad-hoc integration over SPI/TCK discipline.

Reason:
- Exeris depends on boundary integrity (The Wall) to preserve long-term performance and portability.

### 4) Teams needing closed-source Enterprise internals in this repository
Not a fit if you require:
- Enterprise source code in this public/community repository.

Reason:
- Enterprise runtime is distributed separately.

### 5) Teams expecting production-complete Spring-hosted Exeris in this repository today
Not a fit right now if you require:
- Officially in-repo, production-complete Spring runtime integration as a current baseline.

Reason:
- The Spring runtime track exists in a separate repository and is currently in early architecture/bootstrap stage.

## Decision Check (Fast Self-Assessment)

You are likely a fit if you answer yes to at least 3:
- We track cost per request and density metrics.
- We need predictable behavior under pressure, not best-effort degradation.
- We can adopt by subsystem and measurable milestones.
- We accept explicit contracts over hidden framework behavior.
- We need a path from standard Java stack toward lower-copy, lower-allocation runtime.

If you answer yes to 0-1, Exeris is probably not the right first move today.

## Reality Guardrails
- Current repository state includes SPI, Core, Community, and TCK runtime code.
- HTTP codec/runtime implementation is currently in Core.
- Enterprise module is not present in this repository and should be treated as separate distribution scope.
- `exeris-spring-runtime` exists as a separate repository and should be treated as a future adoption track until officially folded into kernel release posture.
