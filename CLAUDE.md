# Exeris Kernel: Repo-Wide Guardrails

Mission: preserve **No Waste Compute**, keep **The Wall** intact, and prefer practical, contract-grounded decisions over style dogma.

## What This Project Is

Exeris Kernel is a cloud-native, zero-copy runtime platform for the JVM (open-core: this repo is the open side). It replaces framework-heavy Java stacks with a system-level kernel: Panama FFM off-heap memory, virtual threads, `ScopedValue` context, JFR-first observability, OpenSSL via FFM. Not a standard Java app — standard Java idioms are often *wrong* here.

- **Stack (this branch):** **JDK 28 EA**, `--enable-preview` on main sources as well as tests — this is the `preview` line, and `PREVIEW-TRACK.md` is its identity doc. The **distributed** line is JDK 25 LTS with preview-clean main sources (ADR-066); do not apply that description here. Maven 3.9+ multi-module reactor, JUnit 5, ArchUnit, JMH, JFR, Testcontainers (Postgres/Kafka) for tagged integration tests.
- **Two distribution tracks, and they pick their JDK by opposite rules.** The default line (`main`, the distributable `1.0`) is preview-clean on **LTS only** — JDK 25 today, 29 next: no `--enable-preview` on main sources, no `StructuredTaskScope`, concurrency on virtual threads + explicit `ScopedValue` rebind (both GA). The **`preview` branch** targets the **newest JDK, LTS or not** — JDK 28 today — with `--enable-preview`, keeps `StructuredTaskScope`, and is where JEP 401 value classes get exercised; it ships as `1.0-preview` for JVM-controlled deployments and *is the intended future `main`*, converging at the LTS where those features go GA. **Which track a statement is about changes what it means**: a mandate to use `StructuredTaskScope` is correct on `preview` and disqualifying on `main`.
- **The substitution is DONE on the default line (v0.11, ADR-066) and deliberately NOT here — this branch keeps all four `StructuredTaskScope` sites, which is its reason to exist. On the default line two of the four did not become forks.** `OutboxOrchestrator` and `CommunityEventLoop` moved to `core.concurrent.StructuredScope`; `InMemoryEventBus.publishAndAwait` and `SubsystemOrchestrator` phase start now run **in-thread**, because their contracts require `ScopedValue` bindings the kernel does not define — an application's own, and a `ScopedValue.Carrier` can only carry values named in advance. **You cannot propagate what you cannot enumerate**: rebuilding the kernel's carrier and forking was tried and booted the HTTP subsystem with no handler bound, every route 404. Verify the state by bytecode, not by grep — `tools/preview-bytecode-scan/preview-bytecode-scan.sh` is the gate, and 26 test fixtures still import `StructuredTaskScope` legitimately. Details: ROADMAP §"Platform Baseline for 1.0 GA".
- **Coordinates:** groupId `eu.exeris.preview` **on this branch** (`eu.exeris` on the distributed line), packages `eu.exeris.kernel.<module>.<subsystem>`, GitHub `exeris-systems/exeris-kernel`. Version here: 0.12.0-SNAPSHOT (latest cut on this line: `0.11.1`); on `main`: 0.11.0; active development: v0.12.
- **Subsystems** (each has a contract doc in `docs/subsystems/`): bootstrap, config, crypto, events, exceptions, flow, graph, http, memory, persistence, scheduling, security, storage, telemetry, transport.
- **Key terms** (`docs/glossary.md` is authoritative): *The Wall* = SPI/implementation separation; *No Waste Compute* = every byte allocated and cycle spent must add value; *software inflation* = abstraction layers without measurable value; *Glass-Box* = JFR events as primary observability, not logs.

## Repository Map and The Wall

Reactor modules (root `pom.xml`) and their import rules:

| Module | Role | May depend on |
|---|---|---|
| `exeris-kernel-spi` | Contracts + carriers ("The Constitution") | **only `java.*` / `jdk.*`** |
| `exeris-kernel-core` | Driver-agnostic orchestration, bootstrap; HTTP codec/runtime currently lives here | SPI. **Never** community/enterprise |
| `exeris-kernel-community` | Open providers (transport, persistence/JDBC, flow, events, security, …) | SPI only. **Never** core internals |
| `exeris-kernel-community-kafka` | Kafka/Redpanda event/flow bindings | SPI, community |
| `exeris-kernel-community-testkit` | Shared test fixtures | — |
| `exeris-kernel-tck` | Contract tests (`Abstract*Tck`) + `ExerisArchitectureTest` (ArchUnit Wall guard) | SPI |
| `exeris-kernel-diagnostics-cli` | Diagnostics tooling (thin, coverage-ungated) | — |
| `exeris-kernel-bom` / `-parent` / `-build-config` | Build plumbing; build-config ships lint rulesets and is itself lint-exempt | — |

Repository realities to respect:
- `exeris-kernel-enterprise` is **not in this repository** (separate closed-source distribution). Never deep-link into enterprise-private repos from public docs.
- Enterprise-facing claims in docs describe the shared Core engine (e.g. FFM/OpenSSL per ADR-008), not code you can read here.
- `tools/jfr-reporter` is CI tooling, outside the reactor.

## Documentation Precedence
Use the smallest sufficient authoritative set first.

1. `docs/modules/*.md` and `docs/subsystems/*.md` for placement and behavior.
2. `docs/adr/*.md` when boundaries, lifecycle model, or module split are affected. `docs/rfc/*.md` for designs still in flight.
3. `docs/whitepaper.md`, `docs/architecture.md`, `docs/performance-contract.md` for philosophy and numeric SLOs.
4. `docs/ROADMAP.md` for current milestone intent and 1.0 GA constraints; `docs/glossary.md` for terminology; `CONTRIBUTING.md` for build/off-heap/debugging mechanics.

If a referenced document is missing or stale, fall back to available module/subsystem/ADR docs and the current source layout — and say so rather than inventing target-state.

## Rule Levels

### A) Hard Constraints (always enforce)
- SPI must remain implementation-blind; no driver/native details leak into SPI contracts.
- Core must remain driver-agnostic and orchestrate through SPI contracts.
- No framework DI in runtime kernel code; use explicit construction and ServiceLoader model.
- No `ThreadLocal` for runtime context propagation; use `ScopedValue`.
- No unstructured concurrency in runtime orchestration paths where structured scope is expected.
- New SPI surface or changed observable SPI behavior requires executable `Abstract*Tck` coverage plus binding tests before merge.
- New `ExerisKernelException` subclasses require a `rawArgs` layout comment and an error code registered in `exeris-kernel-spi/.../spi/exceptions/KernelErrorCodes.java` (single source of truth — no string literals in exception constructors).

### B) Strong Defaults (enforce by default, allow justified exceptions)
- Prefer `StructuredTaskScope` for orchestration concurrency — scoped to JVM-controlled deployments (served via the `1.0-preview` artifact on the `preview` branch, the intended future `main`). The **distributable default artifact must stay preview-clean for 1.0 GA**, so on the default critical path (`main`) prefer the own `fork`/`join`/`cancel` layer over virtual threads + `ScopedValue` (both GA) at the existing seam; a default-path move *away* from `StructuredTaskScope` is not a guardrail violation (see ROADMAP "Platform Baseline for 1.0 GA").
- Prefer `MemorySegment`, `LoanedBuffer`, and `VarHandle` on runtime hot paths.
- Prefer JFR-first instrumentation for subsystem lifecycle/failure points (bootstrap, allocation failure, bind/start, state transitions).
- Prefer expanding TCK coverage when observable SPI behavior changes.
- Design carriers to be Valhalla-ready (`record` / immutable final classes; avoid identity-sensitive operations: no `synchronized` on carriers, no `System.identityHashCode()`, no identity `==` on domain objects). On `main` this is discipline, unenforced; JEP 401 (Value Objects) and JEP 539 (Strict Field Initialization) are **preview** in JDK 28, so the rule becomes executable on the `preview` branch and stays a style rule on the default line. A record whose generated `equals` compares array components by reference violates it as surely as an explicit `==` does — `FlowSnapshot` and `FlowMigrationState` both carry hand-written value equality for exactly that reason.

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
- ad-hoc Arena management (`Arena.ofConfined()` etc.) in subsystem runtime code when approved ownership abstractions exist — it bypasses `WatermarkManager`.
- checked exceptions on hot state-machine paths.
- `String.formatted()` / string concatenation on exception/failure paths — use the `rawArgs[]` primitive layout.
- double-checked locking for lazy init — use the Supplier + `AtomicReference` CAS compute-once pattern (see CONTRIBUTING.md) or `LazyConstant`.

These bans do not automatically apply to test fixtures, build tooling, migration scripts, or debug harnesses. The `ThreadLocal` and `Executors` bans are enforced by ArchUnit (`ExerisArchitectureTest`), not PMD — if the arch guard did not run, nothing has checked them.

## Memory and Ownership Policy
All native memory must have explicit owner and deterministic lifecycle.
In subsystem/runtime code, prefer `MemoryAllocator`, `LoanedBuffer`, or approved native context wrappers over ad-hoc ownership.

`LoanedBuffer` lifecycle is unforgiving (silent leaks, double-free SIGSEGV): always try-with-resources; `retain()` **before** forking a subtask that uses the buffer, `close()` in the subtask. The TCK runs `LeakDetectionMode.PARANOID` — a `LeakDetectedError` means fix the lifecycle, not the test. Full rules: CONTRIBUTING.md §"Off-Heap Memory".

## Testing Model
- **Test triad for features:** unit test + integration test + TCK expansion. A PR touching an SPI boundary with only unit tests is incomplete.
- **TCK pattern:** contract behavior lives in `Abstract*Tck` classes in `exeris-kernel-tck`; each provider module binds them with concrete subclasses. Assert semantics, not just happy paths.
- **Tagged tests do NOT run in the default build.** `@Tag("integration")` (Testcontainers Postgres/Kafka), `@Tag("continuity")`, and `@Tag("stress")` are excluded from `mvn clean install` and run in dedicated CI gates. If you touched code they cover, run them explicitly: `mvn -pl <module> -DincludedGroups=<tag> -DexcludedGroups= test`. A green default build is not proof they pass.
- On Windows, FFM/OpenSSL TLS tests auto-skip; full native coverage needs Linux (`libssl.so.3` on path) — don't claim TLS verification from a Windows run.

## Build, Verify, and Definition of Done

Golden command (the only one that counts — `compile` proves nothing here):

```bash
mvn clean install
```

**Lint gates:** the parent POM binds `checkstyle:check` to `validate` and `pmd:check` to `verify` (both fail on violation), so a full `mvn clean install` with no skip flags IS lint-gated. The footgun is the skip flags: `-Dpmd.skip=true`/`-Dcheckstyle.skip=true` get used for fast iteration and JDK 26 SIGSEGV workarounds, and PMD binds at `verify`, so a `mvn test` loop never reaches it — a build that skipped lint proves nothing about lint. Standalone re-check, scoped to changed modules; never include `exeris-kernel-build-config` in the `-pl` list (parented to `exeris-kernel-root`, so no lint bindings, plus `pmd.skip=true`):

```bash
mvn -pl <changed-modules> pmd:check checkstyle:check
```

**Architecture guard:** run the Wall guard yourself — do not assume CI covers it (it has historically been `@ArchIgnore`'d):

```bash
mvn -q -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpmd.skip=true -Dcheckstyle.skip=true test
```

CI (`.github/workflows/maven.yml`) runs `mvn clean verify -P coverage` (JaCoCo line/branch floors are ratcheted per module — don't lower a floor to make a build pass) plus sequenced gates: persistence RLS → Kafka integration → recovery continuity → transport stress → TLS OpenSSL 3.x/4.x matrix; JMH benchmarks on `main` only.

**Definition of done — all of these, in order, before calling work finished:**
1. `mvn clean install` green (full reactor for cross-module changes; `-pl <module> -am` acceptable for isolated ones).
2. Lint-clean on changed modules — covered by step 1 **unless** any `-Dpmd.skip`/`-Dcheckstyle.skip` was used in the loop; then run the standalone command above. No new PMD/Checkstyle suppressions without written justification.
3. `ExerisArchitectureTest` green.
4. Contract/SPI change → TCK + binding tests updated and green; tagged tests run if their subject changed.
5. Docs/ADR impact triaged (`exeris-doc-impact-triage` skill); drift fixed or explicitly deferred with reason.
6. Release notes/CHANGELOG describe only published behavior — no local-only links, no cross-repo private PR references.

Run the `exeris-pr-preflight` skill before any commit/push/PR — it encodes this checklist as a go/no-go gate. Never report "ready to PR" from a green `verify` alone.

## Branch, PR, and Release Workflow
- **Base branch:** cut a fresh feature branch per task off the current active development base — `development/0.12.0` as of v0.11.0 (bump this line in the release integration PR) — or off `preview` for work that belongs to this line only, or a `research/<slug>` branch for perf research. Never commit directly to `main`; never reuse a merged branch.
- **Branch names:** `feature/…`, `fix/…`, `perf/…`, `docs/…`, `research/…` with a version or ADR prefix when applicable (e.g. `feature/v011-…`, `feature/ADR-046-…`).
- **Commits/PR titles:** conventional style `type(scope): summary` — e.g. `fix(persistence): …`, `docs(adr): …`, `release(0.10.0): …`.
- **Releases:** milestones integrate into `main` via a single `release(x.y.z)` PR; `main` carries release versions, `development/*` carry `-SNAPSHOT`s (both publish to GitHub Packages).
- **ADR/RFC numbers are a GLOBAL namespace across the exeris ecosystem.** Reserve the number in `~/exeris-systems/exeris-docs/adr-index.md` **before** writing content (use the `exeris-adr-register` skill; the ADR-026 collision in PR #129 is the cautionary tale). Filenames are kebab-case: `ADR-0XX-short-title.md`; cross-repo ADRs get `.link.md` stubs.
- **Planning artifacts:** milestone/implementation plans live outside the repo (local plans directory). In-repo planning is only `docs/ROADMAP.md` and `docs/release/*` notes.
- Multiple concurrent sessions on this repo → use git worktrees off a clean base; a parallel branch-switch can revert uncommitted edits.

## Operating Standards (Any Model)
Process discipline that holds regardless of which Claude model runs the session — follow these mechanically; they remove the judgment calls that go wrong first.

1. **Ground truth over meta-docs.** Claims about what the build/CI/tooling *does* are verified against the effective source (`pom.xml` `<executions>`, `.github/workflows/*.yml`, rulesets) — never against CLAUDE.md, skills, or sibling meta-docs; they share ancestry with the claim being checked (the "verify skips PMD" myth survived that circular check from the first commit until PR #238).
2. **Classify scope first.** Runtime hot path / runtime non-hot / test-tooling / docs-only — bans and review depth follow from the class, and every verdict states it.
3. **A claim names the command that proves it.** "Tests pass" cites the exact invocation; a green default build says nothing about tagged tests (`exeris-tagged-gate-runner`), and a skip-flagged build says nothing about lint.
4. **No perf conclusions from one run; no cross-driver comparisons.** Full discipline: `exeris-jfr-perf-research`.
5. **Minimal diffs, matched idiom.** No drive-by refactors or style rewrites; comments only per the comment policy below.
6. **Never invent target-state.** Missing or stale doc → say so and fall back to source layout; docs never outrun code.
7. **Findings carry the why.** Every review finding maps to a doc/ADR/contract clause plus the smallest corrective action.
8. **Escalate at the seams.** Placement doubt → architect lens BEFORE code; SPI/observable-behavior touch → TCK gate; contract-changing work is never self-approved as done.
9. **Report outcome first.** What happened or was found leads the reply; skipped steps and residual risks are stated, never silent.

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
- Skill packs live in `.claude/skills/` (PR-review lenses, `exeris-triage` single-pass triage, subsystem lenses, plus the workflow skills: `exeris-pr-preflight`, `exeris-adr-register`, `exeris-jfr-perf-research`, `exeris-release-integration`, `exeris-tagged-gate-runner`).

### When to use which
These three surfaces overlap on purpose; pick by *how* you need the work done, not *what* it is:
- **Skill** (`Skill` tool, inline) — runs the checklist in the current conversation, keeping full context. Default for review lenses, triage, and the workflow skills above. Start a PR review with `exeris-pr-review-waste-hunter`, which dispatches to the focused lenses. `exeris-pr-preflight` is mandatory before any push/PR; `exeris-adr-register` is mandatory before authoring any ADR/RFC.
- **Agent** (`Agent` tool, delegated) — spins up a separate context window. Use for broad fan-out, read-heavy exploration, or parallel independent work where you only want the conclusion back (e.g. `exeris-architect`, `exeris-performance`).
- **Command** (`/name`, explicit) — user-invoked Community/Open-Core prompt; reach for it when the user types the slash command.
- Skills and agents intentionally mirror the same personas (e.g. `exeris-architect-guardrails` skill ↔ `exeris-architect` agent): same lens, different execution mode.
