# Exeris Community/Open-Core Reusable Prompt Pack

Use this catalog for custom prompts, snippets, or agent handoff templates.

## 1) Community Architecture Review
Review this change as an Exeris Community/Open-Core architecture task.

Context:
- Preserve The Wall: SPI must remain implementation-blind, Core must remain driver-agnostic, Community provides OSS runtime implementations behind SPI contracts.
- Use the smallest sufficient docs first: relevant docs/modules/*.md, docs/subsystems/*.md, then docs/adr/*.md if boundaries or placement are affected.
- Respect current repository realities: Community may contain placeholders; Enterprise may be out-of-repo; HTTP codec is currently embedded in Core.

Please answer in this structure:
1. Placement: should this live in SPI, Core, Community, or TCK?
2. Boundary check: does anything leak implementation detail across The Wall?
3. ADR/contract check: does this align with current architecture intent?
4. Risks: what is the main architectural risk?
5. Minimal safe direction: what is the smallest correct next step?

Do not optimize for elegance first. Optimize for boundary correctness and contract integrity.

## 2) Community Implementer
Implement this as an Exeris Community/Open-Core change.

Constraints:
- Do not re-litigate architecture unless a direct violation is detected.
- Preserve existing module boundaries.
- Prefer explicit construction, ScopedValue, StructuredTaskScope, immutable carriers, and zero-copy/off-heap-safe patterns where relevant.
- Avoid framework DI, ThreadLocal for runtime context, and unstructured orchestration in runtime paths.
- Keep changes minimal and targeted.
- If the change affects SPI-observable behavior, explicitly mark that TCK review is required.

Please provide:
1. implementation plan,
2. target files/modules,
3. smallest code change set,
4. risks or assumptions,
5. proportional verification needed.

## 3) Community TCK-First Review
Review this change with Exeris TCK-first discipline.

Treat TCK as the contract judge.

For this change:
- classify contract impact as NO_CONTRACT_CHANGE, CONTRACT_EXTENSION, or CONTRACT_BREAKING_CHANGE,
- identify affected SPI contracts or observable lifecycle semantics,
- determine whether Abstract*Tck must be added or updated,
- determine whether Core/Community bindings must be added or updated,
- verify that tests prove contract semantics, not only happy path,
- call out zero-alloc, ref-count, leak, or stable error-semantics coverage where relevant.

Output:
1. Contract impact class
2. Affected contracts/symbols
3. Abstract TCK requirements
4. Binding test requirements
5. Weak/missing semantics coverage
6. Verdict: APPROVE / CONDITIONAL / REJECT
7. Minimal merge-blocking fixes

## 4) Community Performance/Memory
Review this Exeris Community change as a runtime hot-path and memory-lifecycle task.

Scope:
- Apply strict review to production runtime and hot paths.
- Distinguish runtime hot paths from test/tooling/fixtures.
- Preserve explicit ownership and deterministic lifecycle for native memory.
- Prefer MemoryAllocator, LoanedBuffer, MemorySegment, VarHandle, and zero-copy flow where relevant.
- Flag hidden heap churn, copy churn, accidental wrappers, ad-hoc ownership, risky blocking, or weak observability.

Please output:
1. Hot-path relevance
2. Allocation/copy risks
3. Memory ownership/lifecycle risks
4. Concurrency/runtime risks
5. JFR/observability expectations if relevant
6. Smallest safe fixes
7. Strong patterns worth keeping

## 5) Community Docs/ADR
Review this Exeris Community/Open-Core change for documentation and ADR consistency.

Rules:
- Keep docs realistic to current repository state.
- Do not document target architecture as implemented fact unless clearly marked as planned/placeholder/repository-state note.
- Use docs/modules/*.md and docs/subsystems/*.md as primary sources.
- Escalate to docs/adr/*.md only when architecture intent, module split, or lifecycle model changes.

Please output:
1. Affected docs
2. Drift classification: NONE / MINOR_DOC_UPDATE / ADR_IMPACT
3. What code reality changed?
4. What docs must change?
5. Minimal patch list (files + sections)

## 6) Open-Core Boundary
Review this change specifically through the Exeris Open-Core boundary.

Questions to answer:
1. Is this capability part of SPI contract, Core shared infrastructure, Community OSS implementation, or Enterprise-only specialization?
2. Does this change accidentally move Enterprise-specific detail into Core or SPI?
3. Does this change weaken the Community experience in a way that violates the open-core intent?
4. Does this align with the current Core/Community/Enterprise split?
5. What is the smallest boundary-safe implementation approach?

Optimize for open-core clarity: Community should remain strong, but Enterprise-only implementation detail must not leak upward.

## 7) Core vs Community Placement
Help decide whether this Exeris change belongs in Core or Community.

Use these principles:
- Core contains shared, driver-agnostic orchestration and infrastructure.
- Community contains OSS runtime implementations behind SPI contracts.
- Core must not hard-code Community implementation details.
- Community may depend on Core, not the other way around.
- If the capability is shared by Community and Enterprise and does not violate The Wall, prefer Core.
- If it is a concrete OSS driver/runtime implementation detail, prefer Community.

Output:
1. Recommended placement
2. Why not the other module?
3. Dependency/boundary implications
4. Smallest safe refactoring path

## 8) SPI Purity
Audit this Exeris SPI change for purity and The Wall compliance.

SPI rules:
- SPI must remain implementation-blind.
- No driver/native/OS-specific details in contracts.
- No Community or Enterprise implementation knowledge.
- Contracts should express behavior and invariants, not implementation mechanism.

Please review:
1. Does the SPI surface leak implementation detail?
2. Are names/types/contracts too concrete to one implementation?
3. Is the behavior defined at contract level?
4. Would this SPI still make sense with multiple runtime implementations?
5. Minimal correction if purity is violated

## 9) Community Provider/ServiceLoader
Review this Exeris Community change as a provider-loading and ServiceLoader task.

Focus on:
- provider discovery through SPI contracts,
- avoiding hard-coded runtime wiring in Core,
- registration correctness,
- preserving Community as a replaceable implementation,
- keeping observable provider behavior testable through TCK or binding tests where applicable.

Output:
1. Loading model check
2. Registration/provider risks
3. Boundary risks
4. Test implications
5. Minimal safe fixes

## 10) Community PR Review
Review this PR as an Exeris Community/Open-Core reviewer.

Priorities:
1. Boundary integrity (SPI/Core/Community/TCK)
2. Contract integrity (subsystem docs + ADR intent)
3. Runtime efficiency on hot paths
4. Verification impact proportional to behavior change
5. Documentation drift if architecture or subsystem reality changed

Please produce:
- Summary
- Blocking issues
- Non-blocking issues
- TCK/test implications
- Performance/memory implications
- Docs/ADR implications
- Final verdict: APPROVE / CONDITIONAL / REJECT

## 11) Community Refactor Safety
Review this refactor for Exeris Community/Open-Core safety.

Assume the goal is to preserve behavior unless explicitly stated otherwise.

Please determine:
1. Is this truly refactor-only, or does it affect observable behavior?
2. Does it change placement or dependency direction?
3. Does it alter memory ownership, runtime lifecycle, or provider semantics?
4. Does it require test updates, TCK review, or docs updates?
5. What is the main hidden risk in this refactor?

Be conservative about contract drift, but do not overstate risk for cosmetic changes.

## 12) Community Subsystem Review
Review this Exeris change in the context of the [SUBSYSTEM_NAME] subsystem.

Instructions:
- Read the relevant docs/subsystems/[SUBSYSTEM_FILE].md first.
- Use docs/modules/*.md to verify placement.
- Escalate to ADRs only if boundaries, lifecycle model, or shared architecture are affected.
- Evaluate both contract correctness and current repository reality.

Please output:
1. Subsystem responsibility match
2. Placement match
3. Contract/behavior risks
4. Runtime/performance risks if relevant
5. Test/TCK implications
6. Minimal safe next step

## Quick Prompts
- Where should this live in Exeris: SPI, Core, Community, or TCK? Explain boundary risks and minimal safe placement.
- Check this change for The Wall violations and dependency inversion. Be specific and minimal.
- Does this change affect observable SPI behavior or provider semantics? If yes, specify exact TCK and binding implications.
- Review this for allocation churn, copy churn, ownership issues, and hot-path latency risks in Exeris runtime code.
- Check whether this change causes doc drift in modules/subsystems/ADRs and list the smallest required updates.
