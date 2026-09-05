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
  branch for performance research. **Resolve that base from the remote, not from a file** —
  `git branch -r --list 'origin/development/*' | sort -V | tail -1`. Nothing in this repository
  names it: `docs/ROADMAP.md` does not mention the branch, and the root `pom.xml` carries whatever
  version the last release flip left behind. A version written into an agent file is the first thing
  to rot. **Never commit directly to `main`; never reuse a merged branch.**
- **Branch names.** `feature/…`, `fix/…`, `perf/…`, `docs/…`, `research/…`, with a version or ADR
  prefix where one applies (`feature/v011-…`, `feature/ADR-046-…`).
- **Commits and PR titles.** Conventional style `type(scope): summary` — `fix(persistence): …`,
  `docs(adr): …`, `release(0.10.0): …`. The binding rules, including subject length and the
  `Motivation:` / `Modification:` / `Result:` body sections, are
  [`commit-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/commit-conventions.md)
  and [`pr-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/pr-conventions.md).
- **Releases.** A milestone integrates into `main` through a single `release(x.y.z)` pull request.
  `main` carries release versions, `development/*` carry `-SNAPSHOT`s; both publish to GitHub
  Packages. This line cuts its own versions under its own coordinates and does not enter that flow;
  a literal merge of `main` into `preview` conflicts on the release squashes, so changes come here
  by cherry-pick.
- **ADR and RFC numbers are a GLOBAL namespace across the Exeris ecosystem.** Reserve the number in
  the registry **before** writing content — use the `exeris-adr-register` skill. The ADR-026
  collision in PR #129 is the cautionary tale. Filenames are kebab-case `ADR-0NN-short-title.md`;
  cross-repo ADRs get `ADR-NNN.link.md` stubs in every affected repository.
- **Planning artefacts live outside the repository.** Milestone and implementation plans stay in the
  local plans directory. In-repo planning is `docs/ROADMAP.md` and `docs/release/*` notes, nothing
  else.
- **Concurrent sessions share this checkout.** Use a git worktree off a clean base; a parallel
  branch switch can revert uncommitted edits in the primary tree.
- **Contribution terms.** External contributions carry a `Signed-off-by:` trailer and are covered by
  the contributor agreement described in [`CONTRIBUTING.md`](../../CONTRIBUTING.md); AI-assisted
  commits keep their `Co-authored-by:` trailer. See
  [`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).
