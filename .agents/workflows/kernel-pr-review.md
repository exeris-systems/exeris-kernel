---
name: kernel-pr-review
description: Review a whole pull request against this repository's own rules and mechanical gates, as the exeris-evaluator role, and answer with a verdict. This is the routine the CI review runs; invoke it directly to review a diff by hand.
argument-hint: PR number, diff, or the changed files to review
steps:
  - {agent: exeris-evaluator}
  - {skill: exeris-triage, when: "the diff crosses more than one subsystem"}
  - {skill: exeris-pr-review-waste-hunter}
  - {agent: exeris-architect, when: "a boundary or a placement is in question"}
  - {agent: exeris-tck, when: "observable SPI behaviour moved"}
  - {agent: exeris-performance, when: "a hot path, an allocation site or a native lifetime was touched"}
  - {agent: exeris-docs-adr, when: "a subsystem contract or an ADR no longer matches the code"}
  - {skill: exeris-tagged-gate-runner, when: "the change is covered by an integration, continuity or stress gate"}
gates:
  - script:tools/spi-contract-blindness-check/spi-contract-blindness-check.sh
  - script:tools/error-code-registry-check/error-code-registry-check.sh
  - script:tools/checkstyle-parity-check/checkstyle-parity-check.sh
  - script:tools/scope-register-check/scope-register-check.sh
  - script:tools/eval-consistency-check/eval-consistency-check.sh
  - hook:guardrails-gate-on-stop
  - ci:maven / build-and-verify
  - ci:guardrails / docs
---

Review this change as the `exeris-evaluator` role
(`.agents/agents/exeris-evaluator/AGENT.md`). That profile is the contract for how you judge; this
file is the order you do it in.

**The criteria are not written here.** They are owned by `.agents/policies/`, by the subsystem
contracts under `docs/subsystems/`, and by the ADRs — and each of those changes more often than a
copy of it would be updated. Read the ones this diff touches. A rule restated here would be a
second place to author it, which is what `agents-md-schema.md` rule 2 forbids and what this routine
exists to end.

## Order

**1. Decide the two things that change every later answer.**

- **Track.** Read the base branch. The default line (`main`, `development/*`) is preview-clean; the
  `preview` branch is not. `.agents/policies/jdk-and-preview-track.md` is authoritative.
- **Scope class.** `runtime hot path | runtime non-hot | test-tooling | docs-only`. A ban applies to
  a scope, not to a string.

**2. Read what the diff touches, in this precedence.**

1. `docs/modules/*.md` and `docs/subsystems/*.md` — placement and behaviour. A subsystem contract
   outranks any summary of it.
2. `docs/adr/*.md` — boundaries, the lifecycle model, the module split.
3. `.agents/policies/` — what is permitted or forbidden here. Each file is the single owner of its
   list; do not work from a remembered subset.
4. A subtree with its own `AGENTS.md` — `exeris-kernel-spi`, `exeris-kernel-core`,
   `exeris-kernel-community`, `exeris-kernel-tck` — adds rules the root file does not repeat.

**3. Run the mechanical checks the diff earns, and report each one.**

Every check below is a CI gate, runs in well under a second, and needs no network or container.
Report each as **pass**, **fail** or **not-run**, with its exit code. *A check you could not run is
reported as not-run — never omitted, and never implied to have passed.*

| the diff touches | run |
|:--|:--|
| `exeris-kernel-spi/src/main/java/**` | `tools/spi-contract-blindness-check/spi-contract-blindness-check.sh` |
| `KernelErrorCodes.java` or `docs/subsystems/exceptions.md` | `tools/error-code-registry-check/error-code-registry-check.sh` |
| either `checkstyle*.xml` | `tools/checkstyle-parity-check/checkstyle-parity-check.sh` |
| `docs/ROADMAP.md` or `docs/release/1.0-scope.md` | `tools/scope-register-check/scope-register-check.sh` |
| `.agents/evals/scenarios.yaml` | `tools/eval-consistency-check/eval-consistency-check.sh` |
| `.agents/**`, `AGENTS.md`, `CLAUDE.md` | nothing here — the agent-file and adapter-render checks run in `docs / docs-lint` from the pinned bundle, which this checkout does not carry. Read that job's result and report it; do not claim to have run them. |

**4. Judge what no script can.**

Ask these in order, and stop at the first that produces a blocker:

- **The Wall.** Does the SPI stay implementation-blind and Core driver-agnostic? Note that
  `coreDoesNotDependOnCommunity` lives in `exeris-kernel-community` and **does not run** under
  `-pl exeris-kernel-tck -am`, so a green architecture run is not evidence about it.
- **Placement.** Right tier? Community concrete classes take the `Community` prefix, and nothing
  checks that.
- **Evidence.** Does a claim in the pull request body name the command that proves it? A build with
  a skip flag anywhere in the loop proves nothing about what it skipped. A green default build is
  not evidence about the `integration`, `continuity` or `stress` gates, because it does not run
  them.
- **Contract coverage.** Observable SPI behaviour that no `Abstract*Tck` asserts is not merge-ready,
  and an `Abstract*Tck` with no binding subclass runs nowhere.
- **Hot path.** Allocation, copy churn, and native memory with a named owner and a deterministic
  release on every path including the failing one.
- **Telemetry.** JFR-first, single-phase commit, payloads secret-safe.
- **Documentation.** Does a subsystem contract still describe the code? Target state is marked as
  target, never reported as shipped.

**5. Answer.**

One review, not many comments. Blockers first, then in-scope improvements, then non-blocking
suggestions, citing `file:line` — a review comment is read once against one diff, which is the one
place a line number does not rot. End with the verdict, and after it the same content as a fenced
`json` block conforming to `.agents/schemas/verdict.schema.json`.

Scope to review:

$ARGUMENTS
