---
title: Policy — error handling, fallback and honest reporting
type: reference
visibility: public
owning-repo: exeris-agents
status: active
last-verified: 2026-09-08
---

# Policy — error handling, fallback and honest reporting

Hard constraints, for every Exeris repository, on what a session does when something fails and on
what it may say afterwards. The failures below are the ones that produce a *confident wrong
answer* rather than an error — the class no exit code catches.

A repository may restrict further, never relax (`agents-md-schema.md`, Resolution and inheritance).

## 1. A check that cannot run is reported, not skipped silently

Name the check, say it did not run, and say why. A verdict, a review or a session summary that
rests on an unrun check states which check that was. This is the field `checks_run` exists for in
`schemas/verdict.schema.json`, and `not-run` is one of its three values on purpose.

## 2. A rate-limited or partial run is not a pass

`lychee` accepts HTTP 429, so a rate-limited link check exits zero having verified nothing; the
same shape appears whenever a tool degrades to "could not check" and reports success. When a run
was partial, report the count it actually covered, not the count it was asked to cover.

## 3. A grep is evidence for what it searched

A pattern run over one file says nothing about the corpus. Before asserting that a correction is
complete, run it over every file that could carry the claim and report the hit counts before and
after. A repository policy may make the sweep itself a default for its own large files; this rule makes
the reporting side of it binding everywhere.

## 4. A failing check is fixed at the target, not at the check

A link that does not resolve is a broken link even when the target obviously exists somewhere; a
rule that fires is a finding even when the finding is inconvenient. Do not exclude the file, relax
the pattern, add a suppression or convert the link to plain text in order to get a green run. If
the check is genuinely wrong, say so and change the check deliberately — `standards/README.md`:
a rule that keeps getting worked around is a bug in the rule, and the pull request that removes it
says so.

For links specifically: cross-repo references take the absolute
`https://github.com/exeris-systems/<repo>/blob/<branch>/<path>` form that ADR-085's 2026-09-05
amendment fixed, and a target that is genuinely private takes a *(content private)* marker
instead of a link (ADR-020, ADR-085 §G.24).

## 5. Retry once, then file it

A flaky script or a transient network failure is retried once. If it fails again, it becomes a
`[DOC DEBT]` issue with the output attached (`standards/issue-conventions.md`) rather than a
sentence in a pull-request thread — the monthly audit counts filed debt and cannot count what was
only mentioned.

## 6. Never report a local run as CI

CI runs on a fresh clone with no sibling repositories, no `standards/_*/` working material and no
local settings. A green run here is evidence about this workstation. Say which one you ran.

## 7. When a source disagrees with the checkout

The higher-order source wins (`AGENTS.md`, documentation precedence) and the lower document
becomes the defect — never the reverse, and never a silent reconciliation in the direction that
makes the current edit look right. Where the checkout cannot settle the question, leave a `VERIFY`
comment at the sentence and report it as doc debt rather than guessing.
