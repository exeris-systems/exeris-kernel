---
title: Branch Protection — required checks and why each one is required
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Branch Protection — required checks

Applies to `main` and to `development/**`. Both are protected by rulesets; `main` additionally
carries a legacy branch-protection record that duplicates the ruleset and should be retired rather
than maintained in parallel.

This page is a record of intent that has to match the repository settings, and on 2026-09-05 it did
not: it named `SonarQube New Code Gate`, which no workflow produces, listed
`Persistence RLS/Interceptor Gate` as required when it was not, and omitted `SPI Compatibility Gate`,
which was. A list of check names nobody re-derives from the settings is a list that drifts.

## Merge settings

- Require a pull request before merging.
- Require at least 1 approving review.
- Dismiss stale pull request approvals when new commits are pushed.
- Require branches to be up to date before merging.
- Require conversation resolution before merging.

## Required status checks

Names must match the workflow's `name:` exactly.

| Check | Produced by | Why it blocks |
|:--|:--|:--|
| `Build & TCK Verification` | `maven.yml` | The reactor and the TCK. Nothing merges past a red build. |
| `SPI Compatibility Gate` | `maven.yml` | japicmp against the release baseline — the stability matrix is only a promise while this runs. |
| `SonarCloud Code Analysis` | SonarCloud app | The quality gate on new code. The *scan* is a step **inside** `Build & TCK Verification` (`SonarQube Cloud Analysis`), so requiring that job already forces the analysis to run; this separate context is the app's verdict on the result, which is a different thing and is required separately. |
| `SonarCloud` | SonarCloud app | The app's second, faster status. Not required — two contexts from one app, and requiring both buys nothing. |
| `Persistence RLS/Interceptor Gate` | `maven.yml` | Row-level security is a security contract; a green build with a broken interceptor is the failure this catches. It is its **own job** (`needs: build-and-verify`), not a step inside the build — requiring `Build & TCK Verification` does not require it, because a ruleset requires check contexts and a job is one context. |
| `Kafka Integration Gate` | `maven.yml` | The Community Kafka binding against a real broker. |
| `Recovery Continuity Gate` | `maven.yml` | Restart and snapshot recovery for `Flow`. |
| `Transport Stress Gate` | `maven.yml` | Native I/O under load — the tier where a regression is silent in unit tests. |
| `docs / docs-lint` | `guardrails.yml` → `exeris-systems/.github` | Frontmatter, filenames, the ADR registry, retracted figures. ADR-085 §J.31. |
| `commits / commit-lint` | `guardrails.yml` → `exeris-systems/.github` | The pull-request title, which is the squash-commit subject that reaches the branch. |
| `pr-body / pr-body-check` | `guardrails.yml` → `exeris-systems/.github` | The classification block parses, so the review can be routed from the body alone. |

The five `maven.yml` gates below `SonarCloud` above already ran on every pull request and blocked
nothing. A gate that runs and cannot fail a merge is an observation, not a gate.

## Deliberately not required

- **`TLS OpenSSL <version>`** — a matrix job, so its check name carries the OpenSSL version.
  Requiring it by name pins the ruleset to a version string and a matrix edit silently drops the
  requirement. It becomes requirable when a summary job with a fixed name gathers the matrix with
  `needs:`.
- **`javadoc-gate`** — red by design on arrival: 62 doclint errors and 100 warnings on
  `exeris-kernel-spi`, measured 2026-09-05. It is the worklist for the Javadoc sweep and becomes
  requirable when that count is zero. Runs under the `javadoc-gate` profile, which exists only for
  this job — the `release` profile keeps `doclint none` so publishing never waits on prose.
- **`docs-review`** — an L2 review (ADR-085 §J.33). It produces findings for a human to weigh, and
  a reviewer's judgement is not a merge gate.
- **`Analyze (java-kotlin)`** (CodeQL), **`Scan PR Dependencies`**, **`security/snyk`** — advisory
  security surfaces. They are watched, not gated, so a third-party advisory database update cannot
  block an unrelated merge on its own. The CodeQL context is `Analyze (java-kotlin)` — read from
  `/repos/.../commits/<sha>/check-runs`, not from `codeql.yml`, whose job is named `Analyze Java`.
  The action renames its own check run, which is exactly why this table is derived from the API.
- **`JMH Benchmarks (Community + Core)`**, **`Parse JFR → Lab JSON`**, **`Publish JFR Data → GH
  Pages`** — run on push and schedule against `main` only, and report `skipping` on a pull request.

## Ruleset hygiene

`development/**` carries three rulesets — `development`, `development/**-1` and `development/**-2` —
of which one is empty and two repeat the same required-checks rule. `main` carries `main-1` (empty)
and `main-2`, plus the legacy protection record. The duplicates are harmless until two of them
disagree, at which point the effective rule is whichever is strictest and nobody can say which file
to edit. Collapse to one ruleset per branch pattern.
