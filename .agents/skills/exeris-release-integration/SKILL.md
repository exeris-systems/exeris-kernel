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

2. **Version flip on the dev branch.** All **13 reactor coordinates** (root + parent + bom + build-config + spi + tck + core + community + community-kafka + community-testkit + diagnostics-cli + native-smoke + native-smoke-kafka): `X.Y.0-SNAPSHOT` → `X.Y.0`.
   - Verify completeness: `grep -r "X.Y.0-SNAPSHOT" --include=pom.xml .` must return nothing.

3. **Release notes.** `docs/release/vX.Y.0-release-notes.md` (the established pattern — see `v0.7.0` through `v0.10.0`) describing **only published behavior** — no local-only links, no private cross-repo PR references, no unpublished-consumer behavior framed as shipped.

4. **Repo-doc sync in the same PR.**
   - `docs/ROADMAP.md` milestone status.
   - Nothing needs bumping for the base branch: no file in this repository names the active
     development line, by design — it is resolved from the remote
     (`git branch -r --list 'origin/development/*' | sort -V | tail -1`). Do **not** "fix" that by
     writing the new version into `docs/ROADMAP.md` or an agent file; that is the drift
     `.agents/policies/branch-and-release.md` exists to prevent. Cutting the next
     `development/X.Y.0` branch is what makes the resolver return it.

5. **The integration PR.** Single PR `release(x.y.z): integrate vX.Y.0 milestone into main`, base `main`, head `development/X.Y.0`. Run `exeris-pr-preflight` before pushing. Never commit to `main` directly. **`JMH Benchmarks (Community + Core)` runs on this PR** — it is the only check in the workflow that would notice a milestone's worth of accumulated regression, and a pull request into `main` is the last point at which that is still cheap to act on. It does not block (JMH on a shared runner is noisier than the regressions worth catching) and publishes nothing; read its run artifacts rather than waiting for a figure to appear anywhere.

6. **Post-merge verification on main.**
   - GitHub Packages publish succeeded **in main's run for the release merge** — downstream repos consume the BOM; a missing/unauthorized artifact breaks them. Check the published version's creation time against that run, not the step's colour: the version is immutable, so a stale upload from elsewhere would turn the release's own upload into a refused 409.
   - Tag state matches convention (prior releases are tagged on main — confirm before assuming).

7. **Open the next development line.** Branch `development/X.(Y+1).0` off the **finalized** release state on main — post-release fixes (e.g. #233 after 0.10.0) may move the tip; cut from the final commit, not the merge commit. One commit: `chore(release): open development/X.(Y+1).0 — bump reactor to X.(Y+1).0-SNAPSHOT` (all 13 coordinates; grep-verify as in step 2). Push the branch.
   - **After 0.13.0 the next line is `development/1.0.0` at `1.0.0-SNAPSHOT`, not `development/0.14.0`** (ADR-065 A1.3). Use the same one-commit shape with `1.0.0` for `X.(Y+1).0`. The base resolver (`sort -V | tail -1`) orders `development/1.0.0` after `development/0.13.0`, so nothing else names the new line; `tools/release-tag/release-tag.sh --self-test` asserts that ordering.

8. **Never reuse the integrated branch.** `development/X.Y.0` is done; all new work cuts fresh branches off `development/X.(Y+1).0`.
   - **Exception: the 1.0 line.** `development/1.0.0` stays open after a release candidate and is done only at `v1.0.0`; fixes found during RC land on it (see step 9).

9. **Release candidates for 1.0.0** (ADR-065 A1). A candidate goes through the same ritual as a release, once per candidate:
   - A `release(1.0.0-RCn): integrate development/1.0.0 into main` PR whose poms carry `1.0.0-RCn` (all 13 coordinates; grep-verify as in step 2). `development/1.0.0` itself stays at `1.0.0-SNAPSHOT`; the RC version exists on that PR's head and on `main`.
   - Tag `v1.0.0-RCn` on the resulting commit on `main`. The release workflow accepts exactly `vMAJOR.MINOR.PATCH` and `vMAJOR.MINOR.PATCH-RC<n>` (`tools/release-tag/release-tag.sh`), requires the tag's commit to be on `main` and the root pom's version to equal the tag without its `v`, and uploads with `autoPublish=false`: the deployment is published or dropped by hand in the portal.
   - A published candidate cannot be withdrawn. A defect found in `RCn` is fixed in `RC(n+1)`; never re-tag.
   - Fixes land on `development/1.0.0` through ordinary PRs, then reach `main` in the next candidate's `release(1.0.0-RC(n+1))` PR. `v1.0.0` is the same PR with the poms at `1.0.0`.

## Output
Checklist status per step (DONE / SKIPPED+reason), the release PR URL, and the new development branch name.

## Non-Negotiable Rules
- Release notes describe only published behavior.
- A release candidate is never re-tagged and never cut from a branch other than `main`.
- Version flip covers all 13 coordinates, grep-verified — a missed module publishes a stale SNAPSHOT.
- The next dev line is cut from the finalized release state, not an intermediate commit.
- `exeris-pr-preflight` runs before the integration PR is pushed.
