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
- **`javadoc-gate`** — written to arrive red, arriving green, and the second of those is the more
  useful state.
  Gated modules are `exeris-kernel-spi` and `exeris-kernel-tck` — the two published surfaces that
  are neither Core, Community nor tooling, which is the set `javadoc-conventions.md` rule 11 sends
  to diff-aware checking instead. Every jar module here reaches Maven Central, so "published"
  alone narrows nothing; rule 11 is what narrows it.

  **What it measured, and what it measures now.** On 2026-09-05: SPI **62 doclint errors, 100
  warnings**, TCK **13 and 100** (javadoc caps warnings at 100, so both were floors), plus **644
  Checkstyle violations** on the SPI — 383 `@since` in `major.minor.patch` against rule 4, 60
  `<pre>{@code}` blocks against rule 8, 60 missing comments, 46 missing `@return`, 40 block tags
  out of order, 21 empty descriptions. Rule 6's tag vocabulary was effectively unwritten: one
  `@apiNote` across 276 files, no `@implSpec`, no `@implNote`.

  Every one of those is now zero. The javadoc sweep in #456, #464 and #465 cleared the list before
  this gate reached `main`, and the same sources today report **0 doclint errors, 0 warnings under
  `failOnWarnings`, and 0 Checkstyle violations** on both gated modules against the bundle ruleset;
  the SPI carries 217 `@apiNote`, 235 `@implSpec` and 144 `@implNote`. The numbers above are kept
  as the record of what the gate was standing up against, not as its current cost.

  That changes what the check is for. It was argued for as a worklist — a gate nobody can fail
  teaches nothing — and it lands with the worklist already empty, so its job is the other one:
  holding the zero. It becomes requirable now rather than eventually, and that is a
  branch-protection change rather than a workflow one.

  The three contract lines of rule 3 (Allocation, Thread confinement, Ownership, in that order)
  and rule 6's vocabulary are `[L2]`: no gate produces those numbers, and the gate's silence about
  them is still not evidence of compliance.

  **Where the profile lives.** In `exeris-kernel-spi/pom.xml` and `exeris-kernel-tck/pom.xml`, one
  copy each, not in the root pom — the gate invokes `-pl <modules> -am`, and `-am` drags every
  dependency into the reactor, so from the root the profile gated `exeris-kernel-build-config` too
  and the first run failed there without ever reaching the SPI. The `release` profile keeps
  `doclint none` so publishing never waits on prose. It reported `0 violations` until 2026-09-05, from three separate faults: the
  bundle's rule-8 message contained braces and Checkstyle renders messages through `MessageFormat`,
  so the audit threw `can't parse argument number: @snippet` and stopped after two files; its
  `SuppressionFilter` named `${config_loc}`, which is unset when the config lives outside the
  project, so the config would not parse at all; and this repository's parent pom hard-coded
  `<configLocation>`, which silently overrides `-Dcheckstyle.config.location` — a bogus path did
  not even fail. That last one is fixed here by making it a property, and it is the general lesson:
  a gate that reports clean has to be shown finding something before the report is believed.
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
