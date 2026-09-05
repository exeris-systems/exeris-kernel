---
# DO NOT EDIT — generated from .agents/skills/exeris-pr-preflight/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-pr-preflight
description: 'Pre-PR gate for Exeris Kernel — run BEFORE opening or pushing any PR. Enforces lint-gate integrity (skip flags poison a green build''s lint proof), the architecture guard test, fresh-branch-per-task discipline, and the register-ADR-before-claiming rule. Use whenever about to commit/push a branch or open a PR in the kernel repo.'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-pr-preflight/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris PR Preflight

## Purpose
Catch the recurring, expensive footguns that a green build alone does **not** prove are absent (lint skipped via flags, ignored arch rules, tagged-test gaps), before a PR is opened. This is a workflow skill (it runs checks), not a review-lens.

## When to Use
- About to push a branch or open a PR in `exeris-kernel`.
- Finished a change and want a single deterministic pre-merge checklist.
- Unsure whether lint/arch gates were actually exercised.

## Mandatory Checks (in order)

1. **Branch hygiene — fresh branch per task**
   - Confirm work is on a fresh feature branch cut off the current active development base (the `development/<active-ver>` branch on origin — resolve it with `git branch -r --list 'origin/development/*' | sort -V | tail -1`, because no file in this repository names it; see `.agents/policies/branch-and-release.md`), or the current `research/<slug>` branch — NOT an already-merged branch or directly on `main`.
   - If multiple Claude sessions may share this tree, prefer a git worktree off a clean base — a parallel branch-switch/commit can revert uncommitted edits.

2. **Lint gate — confirm the proof was not poisoned by skip flags**
   - The parent POM binds `checkstyle:check` to `validate` and `pmd:check` to `verify` (both fail on violation): a full `mvn clean install` with no skip flags IS lint-clean.
   - The footgun: `-Dpmd.skip=true`/`-Dcheckstyle.skip=true` get used for fast iteration and SIGSEGV workarounds, and PMD binds at `verify`, so a `mvn test` loop never reaches it. If any build in the session used skips — or you are unsure — re-check standalone, scoped to the changed modules (spi/core/community/etc.), and **exclude `exeris-kernel-build-config`** (it ships the rulesets, is `pmd.skip`, and is not checkstyle-gated; APT processors there inherit that exemption):
     ```
     mvn -pl <changed-modules> pmd:check checkstyle:check
     ```
   - On JDK 26 sporadic SIGSEGV during PMD/G1: just retry, or for long chains add `-Dpmd.skip=true -Dcheckstyle.skip=true` and run lint separately on a short scope.

3. **Architecture guard test (The Wall)**
   - Run the ArchUnit guard so SPI/Core/Driver boundaries are enforced (it has historically been `@ArchIgnore`'d — do not assume CI covers it):
     ```
     mvn -q -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest **plus the Community-module guards** (`KernelTierDirectionArchitectureTest`, `CommunitySchedulingArchitectureTest`, `KernelTierBanArchitectureTest`) — `-pl exeris-kernel-tck -am` does not build Community, so the Core→Community direction rule never runs under it; ExerisArchitectureTest \
       -Dsurefire.failIfNoSpecifiedTests=false \
       -Dpmd.skip=true -Dcheckstyle.skip=true test
     ```

4. **Contract change → TCK present**
   - If the diff touches SPI/observable behavior, confirm `Abstract*Tck` coverage + bindings exist (delegate to `exeris-tck-first` if unsure). No contract change merges without executable TCK implications.

5. **Docs / ADR drift**
   - If behavior/boundary meaning changed, confirm docs/ADR are synced (delegate to `exeris-docs-adr-check`).
   - If an ADR is involved: numbers are a GLOBAL namespace — reserve the slot in `~/exeris-systems/exeris-docs/adr-index.md` BEFORE writing content (see `exeris-adr-register`). PR #129 (ADR-026 collision) is the cautionary tale.

6. **Public-docs hygiene**
   - Release notes/CHANGELOG describe only what is published: no unpublished-consumer-behavior-as-shipped, no local-only links, no cross-repo/private PR# leaks, no deep links into enterprise-private repos.

## Output
A go/no-go summary: each check → PASS / FAIL / N/A, with the exact failing command output and the minimal fix. Do not report "ready to PR" unless lint, arch guard, and (if applicable) TCK are all green or explicitly N/A with reason.

## Non-Negotiable Rules
- Never equate a build that used `-Dpmd.skip`/`-Dcheckstyle.skip` with lint-clean.
- Never include `exeris-kernel-build-config` in the `-pl` module list when running lint.
- Never open a PR that changes a contract without TCK implications.
- Never claim an ADR number before reserving it in the global index.
