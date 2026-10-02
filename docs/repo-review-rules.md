---
title: "Review rules for exeris-kernel"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-23
---

# Review rules for `exeris-kernel`

The `repo-routine` extension of `exeris-systems/.github`'s `docs-guardrails-review.md`, applied
**after** its steps and under its severity tags, output format and verdict schema. It adds checks
and raises severities; it lowers nothing and skips nothing. One review, one verdict, one publisher
— the extension exists so that having rules of one's own is not a reason to keep a review of one's
own.

**The criteria are not authored here.** They are owned by `.agents/policies/`, by the subsystem
contracts under `docs/subsystems/`, by the nested `AGENTS.md` files and by the ADRs. This file
names the questions a reviewer must reach for and the severity each answer carries; the order of
work is `.agents/workflows/kernel-pr-review.md` and the contract for how to judge is
`.agents/agents/exeris-evaluator/AGENT.md`. A rule copied here would be a second place to author
it, which `agents-md-schema.md` rule 2 forbids.

## What this repository is answerable for

A runtime kernel and the contract a downstream implementor codes against. Four things live here and
nowhere else: the boundary between the Spring-free kernel and everything that hosts it, the SPI
surface a driver author implements, the hot path where an allocation is a cost rather than a
detail, and the native memory whose lifetime no garbage collector manages. A reviewer applying only
the shared routine can judge this repository's prose, its pull request bodies and its commits, and
can say nothing about any of the four.

## Step K — rules of this repository

K1. **The Wall holds in both directions** (ADR-006, `.agents/policies/the-wall.md`). A Spring type,
    a servlet API or a DI container reaching `exeris-kernel-spi` or `exeris-kernel-core`, or a Core
    class naming a Community one → `[HARD BLOCK]`. The ArchUnit suite that catches the last of
    those lives in `exeris-kernel-community` and does not run in a reactor build that names only
    the TCK and its ancestors, so a green architecture run is not evidence about it: name the suite
    that ran, or report the question as unchecked.

K2. **The distribution track is established before any rule that mentions a preview feature is
    applied.** `.agents/policies/jdk-and-preview-track.md` is the owner; the base branch decides.
    The same code is correct on the `preview` line and disqualifying on the GA line, so a preview
    language or API feature reaching a GA-line base is `[HARD BLOCK]` and a review that applies
    either rule without naming the base branch has decided nothing → `[STYLE]`.

K3. **Observable SPI behaviour is asserted by the TCK, and the TCK is bound.** An SPI contract that
    moves with no `Abstract*Tck` case → `[CONTRACT]`. An `Abstract*Tck` with no binding subclass
    runs nowhere and is not coverage → `[CONTRACT]`. A positive case beside a disabled one does not
    discriminate: both pass against an implementation that ignores the input the change is about,
    so a test pair that cannot fail on the defect it names is `[CONTRACT]` rather than coverage.

K4. **Native memory has a named owner and a release on the failing path.** An `Arena`, a segment or
    an off-heap buffer acquired without a deterministic release on every path, the throwing one
    included → `[HARD BLOCK]`. Ownership stated in prose and not in code is `[CONTRACT]`: the
    reader of a downstream driver has only the code.

K5. **A JFR event is committed in one phase, and at the site it measures.** `begin()`, a blocking
    operation, then `commit()` on a virtual thread straddles a mount point and crashes the JVM, so
    a new or moved event in that shape is `[HARD BLOCK]`. An emit placed where a `throw` can pass
    between the operation and the event leaves the miss branch dark and the counter honest-looking
    → `[CONTRACT]`.

K6. **A claim names the command that produced it, and that command ran what the claim is about.** A
    build carrying a skip flag anywhere in the loop proves nothing about what it skipped; a green
    default build is not evidence about the `integration`, `continuity` or `stress` gates, because
    it does not run them; and `-Dtest` activates a targeted-test-run profile, so a run narrowed
    that way is measuring a different build from the one CI performs. A *Verification* section
    whose command cannot have produced its claim → `[HARD BLOCK]`; one merely thinner than the
    change → `[STYLE]`.

K7. **Every script in `REPOSITORY CHECK OUTPUT` is reported by name.** The four scripts CI runs for
    the reviewer are evidence, not a verdict — most locate candidates rather than decide. A script
    the output carries and `checks_run` omits → `[STYLE]`; one reported as having passed where the
    output does not show it passing → `[HARD BLOCK]`. The agent-file and adapter-render checks are
    not among them: they run in `docs / docs-lint` from the pinned bundle, which the reviewing
    checkout does not carry, so their result is read from that job and never claimed as run here.

## Where this does not apply, and what it costs

Not to the shared routine's own steps — pull request body, documentation, records, commits,
hygiene — which `docs-guardrails-review.md` judges and which are not restated here: a rule in two
places drifts in one of them. Not to the organisation's gate configuration or its rulesets, which
live in `exeris-systems/.github` and are judged where they live. Not to what the `preview` line
does with a preview feature, which K2 defers to the policy rather than deciding.

The cost is that K1, K2, K4, K5 and K6 are prose a reviewer applies, not a program. K3 and K7 are
mechanical in part and are named here so that a reviewer reports them rather than assuming CI did:
`REPOSITORY CHECK OUTPUT` carries what four of this repository's scripts found, and the TCK binding
question has no script at all. "Checkable, not checked" is the state to say out loud.
