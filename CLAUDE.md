# Exeris Kernel: Repo-Wide Guardrails

Mission: preserve **No Waste Compute**, keep **The Wall** intact, and prefer practical, contract-grounded decisions over style dogma.

## Documentation Precedence
Use the smallest sufficient authoritative set first.

1. `docs/modules/*.md` and `docs/subsystems/*.md` for placement and behavior.
2. `docs/adr/*.md` when boundaries, lifecycle model, or module split are affected.
3. `docs/whitepaper.md`, `docs/architecture.md`, `docs/performance-contract.md` when present and relevant.

If any referenced document is missing or stale, fall back to available module/subsystem/ADR docs and the current source layout.

Current repository realities to respect:
- `exeris-kernel-community` may contain placeholders.
- `exeris-kernel-enterprise` may be out-of-repo.
- HTTP codec/runtime code is currently embedded in Core in this repository state.

## Rule Levels

### A) Hard Constraints (always enforce)
- SPI must remain implementation-blind; no driver/native details leak into SPI contracts.
- Core must remain driver-agnostic and orchestrate through SPI contracts.
- No framework DI in runtime kernel code; use explicit construction and ServiceLoader model.
- No `ThreadLocal` for runtime context propagation; use `ScopedValue`.
- No unstructured concurrency in runtime orchestration paths where structured scope is expected.

### B) Strong Defaults (enforce by default, allow justified exceptions)
- Prefer `StructuredTaskScope` for orchestration concurrency.
- Prefer `MemorySegment`, `LoanedBuffer`, and `VarHandle` on runtime hot paths.
- Prefer JFR-first instrumentation for subsystem lifecycle/failure points (bootstrap, allocation failure, bind/start, state transitions).
- Prefer expanding TCK coverage when observable SPI behavior changes.
- Design carriers to be Valhalla-ready (`record` / immutable final classes; avoid identity-sensitive operations).

### C) Heuristics (signals, not hard gates)
- Class with more than ~5 meaningful collaborators may indicate software inflation.
- O(n) work on hot paths may indicate latency risk.
- New abstraction layers must justify measurable value.
- ADR update may be needed when architecture intent changes.

## Scoped Bans (Production Runtime Hot Paths)
When in doubt, classify scope first: runtime hot path, runtime non-hot path, test/tooling, or docs-only.

The following are banned in production runtime hot paths unless explicitly justified by subsystem contract or test-only/tooling scope:
- `ExecutorService`, `Executors`, `CompletableFuture` (when replacing structured orchestration).
- `java.io.*`, `java.net.Socket`, `ByteBuffer` (when used in zero-copy runtime paths).
- `sun.misc.Unsafe`.
- ad-hoc Arena management in subsystem runtime code when approved ownership abstractions exist.
- checked exceptions on hot state-machine paths.

These bans do not automatically apply to test fixtures, build tooling, migration scripts, or debug harnesses.

## Memory and Ownership Policy
All native memory must have explicit owner and deterministic lifecycle.
In subsystem/runtime code, prefer `MemoryAllocator`, `LoanedBuffer`, or approved native context wrappers over ad-hoc ownership.

## Review Priorities
1. Boundary integrity (SPI/Core/Drivers, The Wall).
2. Contract integrity (subsystem docs + ADR intent).
3. Runtime efficiency on hot paths (allocation/copy/concurrency discipline).
4. Verification impact (unit/integration/TCK proportional to behavior change).
5. Style and readability (with preference for clarity over dogma).
6. Test coverage (with preference for meaningful semantics over 100% line coverage).
7. Documentation updates (with preference for minimal necessary updates to maintain accuracy).
8. PMD/Checkstyle/SpotBugs warnings (with preference for addressing real issues over silencing noise) resolved.

## Comment and Explanation Policy
- Keep code comments minimal.
- Allow comments for contract Javadocs, tricky memory math, ABI/binary layout constraints, and concurrency invariants.
- In reviews, explain findings with "why" grounded in Exeris docs and ADRs.

## SonarQube MCP Guidelines
When the SonarQube MCP server is available:

- After finishing code changes at the end of a task, call `analyze_file_list` (if available) to analyze created/modified files.
- When starting a new task, disable automatic analysis with `toggle_automatic_analysis` (if available).
- When done generating code at the end of a task, re-enable automatic analysis with `toggle_automatic_analysis` (if available).
- When a user mentions a project key, use `search_my_sonarqube_projects` first to find the exact project key — do not guess project keys.
- After fixing issues, do not attempt to verify them via `search_sonar_issues_in_projects`; the server will not yet reflect updates.
- SonarQube requires USER tokens (not project tokens). On `Not authorized`, verify token type.

## Agents, Commands, and Skills
- Functional subagents live in `.claude/agents/` (Architect, Implementer, TCK/Test, Performance/Memory, Docs/ADR, Router).
- Reusable slash commands live in `.claude/commands/` (Community/Open-Core review and implementation prompts).
- Skill packs live in `.claude/skills/` (PR-review lenses, routing/planner, subsystem lenses, plus the `exeris-pr-preflight` / `exeris-adr-register` / `exeris-jfr-perf-research` workflow skills).

### When to use which
These three surfaces overlap on purpose; pick by *how* you need the work done, not *what* it is:
- **Skill** (`Skill` tool, inline) — runs the checklist in the current conversation, keeping full context. Default for review lenses, triage, and the workflow skills above. Start a PR review with `exeris-pr-review-waste-hunter`, which dispatches to the focused lenses.
- **Agent** (`Agent` tool, delegated) — spins up a separate context window. Use for broad fan-out, read-heavy exploration, or parallel independent work where you only want the conclusion back (e.g. `exeris-architect`, `exeris-performance`).
- **Command** (`/name`, explicit) — user-invoked Community/Open-Core prompt; reach for it when the user types the slash command.
- Skills and agents intentionally mirror the same personas (e.g. `exeris-architect-guardrails` skill ↔ `exeris-architect` agent): same lens, different execution mode.
