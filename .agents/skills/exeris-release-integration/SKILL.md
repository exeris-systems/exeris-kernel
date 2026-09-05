---
name: exeris-release-integration
description: Milestone release flow for Exeris Kernel — flip versions, write release notes, integrate development/X.Y.0 into main via a single release(x.y.z) PR, then open the next development line. Use when asked to prepare, cut, integrate, or finalize a milestone release, or to open the next development branch.
---

# Exeris Release Integration

## Purpose
Make the recurring milestone→main integration deterministic. The flow below matches how 0.9.0 (#207) and 0.10.0 (#232 + follow-up a389e37) actually landed; every step exists because skipping it has a concrete downstream cost.

## When to Use
- All milestone PRs are merged into `development/X.Y.0` and the release is being cut.
- Asked to "open the next development line" after a release landed.
- Asked to verify a release is fully finalized.

## Inputs
- Milestone version `X.Y.0` and the current `development/X.Y.0` branch state.
- CI status on that branch (default build AND the sequenced tagged gates — check the latest run, a green local build proves neither).

## Steps (in order)

1. **Preconditions gate.**
   - Every milestone-pinned PR merged into `development/X.Y.0`; ROADMAP milestone section reflects final scope (delivered vs deferred, with deferrals stated).
   - Latest CI run on the dev branch green including tagged gates. If a gate was red-then-deflaked, note it in the release notes.

2. **Version flip on the dev branch.** All **11 reactor coordinates** (root + parent + bom + build-config + spi + tck + core + community + community-kafka + community-testkit + diagnostics-cli): `X.Y.0-SNAPSHOT` → `X.Y.0`.
   - Verify completeness: `grep -r "X.Y.0-SNAPSHOT" --include=pom.xml .` must return nothing.

3. **Release notes.** `docs/release/vX.Y.0-release-notes.md` (the established pattern — see `v0.7.0` through `v0.10.0`) describing **only published behavior** — no local-only links, no private cross-repo PR references, no unpublished-consumer behavior framed as shipped.

4. **Repo-doc sync in the same PR.**
   - `docs/ROADMAP.md` milestone status.
   - No agent file carries the active development version any more: `.agents/policies/branch-and-release.md`
     points at the root `pom.xml` and `docs/ROADMAP.md` instead, so there is no "Base branch" line to bump.
     Check that those two do name the new line.

5. **The integration PR.** Single PR `release(x.y.z): integrate vX.Y.0 milestone into main`, base `main`, head `development/X.Y.0`. Run `exeris-pr-preflight` before pushing. Never commit to `main` directly.

6. **Post-merge verification on main.**
   - GitHub Packages publish succeeded — downstream repos consume the BOM; a missing/unauthorized artifact breaks them (a sibling project has already hit a 401 on `exeris-kernel-bom`).
   - JMH benchmarks job ran (main-only).
   - Tag state matches convention (prior releases are tagged on main — confirm before assuming).

7. **Open the next development line.** Branch `development/X.(Y+1).0` off the **finalized** release state on main — post-release fixes (e.g. #233 after 0.10.0) may move the tip; cut from the final commit, not the merge commit. One commit: `chore(release): open development/X.(Y+1).0 — bump reactor to X.(Y+1).0-SNAPSHOT` (all 11 coordinates; grep-verify as in step 2). Push the branch.

8. **Never reuse the integrated branch.** `development/X.Y.0` is done; all new work cuts fresh branches off `development/X.(Y+1).0`.

## Output
Checklist status per step (DONE / SKIPPED+reason), the release PR URL, and the new development branch name.

## Non-Negotiable Rules
- Release notes describe only published behavior.
- Version flip covers all 11 coordinates, grep-verified — a missed module publishes a stale SNAPSHOT.
- The next dev line is cut from the finalized release state, not an intermediate commit.
- `exeris-pr-preflight` runs before the integration PR is pushed.
