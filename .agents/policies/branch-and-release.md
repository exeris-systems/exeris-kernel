---
title: Policy — branching, pull requests and releases
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — branching, pull requests and releases

- **Base branch.** Cut a fresh branch per task off `preview` when the work belongs to this line
  only, off the current active development base when it belongs to both, or a `research/<slug>`
  branch for performance research. **Resolve the development base from the remote, not from a
  file** — `git branch -r --list 'origin/development/*' | sort -V | tail -1`. Nothing in this
  repository names it: `docs/ROADMAP.md` does not mention the branch, and the root `pom.xml` carries
  whatever version the last release flip left behind. A version written into an agent file is the
  first thing to rot, which is why the old `CLAUDE.md` line had to be bumped in every release pull
  request. **Never commit directly to `main`; never reuse a merged branch.**
- **Branch names.** `feature/…`, `fix/…`, `perf/…`, `docs/…`, `research/…`, with a version or ADR
  prefix where one applies (`feature/v011-…`, `feature/ADR-046-…`).
- **Commits and PR titles.** Conventional style `type(scope): summary` — `fix(persistence): …`,
  `docs(adr): …`, `release(0.10.0): …`. The binding rules, including subject length and the
  `Motivation:` / `Modification:` / `Result:` body sections, are
  [`commit-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/commit-conventions.md)
  and [`pr-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/pr-conventions.md).
- **Releases.** A milestone integrates into `main` through a single `release(x.y.z)` pull request.
  `main` carries release versions, `development/*` carry `-SNAPSHOT`s; both publish to GitHub
  Packages. This line cuts its own versions under its own coordinates and does not enter that flow.
  Shared work reaches it by merge-up ([`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md)): each milestone's
  release commit on `main` is merged here once, which also makes it an ancestor, so the next
  merge-up computes its base from that release rather than replaying it. A change that lands on
  `main` between releases comes here by cherry-pick.
- **Release candidates and the 1.0 line.** After 0.13.0 the next development line is
  `development/1.0.0` at `1.0.0-SNAPSHOT`, not `development/0.14.0`; the base-branch resolver above
  returns it unchanged because `sort -V` orders it after `development/0.13.0`. A release candidate
  is the tag `vMAJOR.MINOR.PATCH-RC<n>` (upper-case `RC`, decimal `n`), and the release workflow
  accepts that shape and `vMAJOR.MINOR.PATCH` only (`tools/release-tag/release-tag.sh`).
  - *Where RC tags are cut.* On `main`, on the commit of a `release(1.0.0-RCn)` pull request that
    integrates `development/1.0.0` with the poms at `1.0.0-RCn`; the workflow refuses a tag whose
    commit is not on `main` or whose pom version differs from the tag. `development/1.0.0` stays at
    `1.0.0-SNAPSHOT`. A candidate is uploaded to Central with `autoPublish=false` like a release, and
    cannot be withdrawn once published: a defect found in `RCn` is fixed in `RC(n+1)`, never by
    re-tagging.
  - *What an RC freezes.* Stable SPI surfaces only: `spi-api-diff --fail-on-stable` against the last
    final release tag (a candidate is never the baseline). Preview and experimental surfaces may
    still change and are named in the next candidate's release notes. Behavioural compatibility is
    outside the gate.
  - *How fixes land during RC.* On `development/1.0.0` through ordinary pull requests, and onto
    `main` in the next candidate's `release(1.0.0-RC(n+1))` pull request. The 1.0 line is not done
    until `v1.0.0`, so the rule against reusing an integrated branch does not apply to it.
- **ADR and RFC numbers are a GLOBAL namespace across the Exeris ecosystem.** Reserve the number in
  the registry **before** writing content — use the `exeris-adr-register` skill. The ADR-026
  collision in PR #129 is the cautionary tale. Filenames are kebab-case `ADR-0NN-short-title.md`;
  cross-repo ADRs get `ADR-NNN.link.md` stubs in every affected repository.
- **Planning artefacts live outside the repository.** Milestone and implementation plans stay in the
  local plans directory. In-repo planning is `docs/ROADMAP.md` and `docs/release/*` notes, nothing
  else.
- **Concurrent sessions share this checkout.** Use a git worktree off a clean base; a parallel
  branch switch can revert uncommitted edits in the primary tree. **Put the worktree outside the
  repository** — a sibling directory such as `../exeris-kernel-wt-<slug>`, the shape already used
  for `exeris-kernel-wt-0.7.1`. A worktree under `.claude/worktrees/` is git-ignored but still on
  disk, and `agents_file_check.py` walks the working tree: it reads that worktree's own `AGENTS.md`
  as a *nested* one and fails it against the 4 KB nested limit. Reproduced — `nested AGENTS.md is
  7 KB (limit 4 KB), 1 errors` — so a repo-root gate run is only clean while no worktree is
  unpacked.
- **Contribution terms.** External contributions carry a `Signed-off-by:` trailer and are covered by
  the contributor agreement described in [`CONTRIBUTING.md`](../../CONTRIBUTING.md); AI-assisted
  commits keep their `Co-authored-by:` trailer. See
  [`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).
