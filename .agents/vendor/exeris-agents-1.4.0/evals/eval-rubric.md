---
title: Eval rubric — grading an agent response
type: reference
visibility: public
owning-repo: exeris-agents
status: active
last-verified: 2026-09-08
---

# Eval rubric — grading an agent response

`agents-md-schema.md` rule 14. `run.py` grades deterministically: equality, membership, counts.
This rubric exists for the residue — the prose half of a finding, which no assertion reaches — and
is used by a judging model only where a deterministic grader cannot decide.

**Reach for a deterministic grader first.** Nearly every property below has a mechanical form: a
verdict's `decision`, a `not-run` in `checks_run`, a forbidden string, a minimum number of
findings. Every case that can be written as an assertion must be, because a rubric run costs a
model call and returns a number nobody can reproduce.

## What the judge scores

Each is 0, 1 or 2. A case passes at 2 on **Grounding** and **Actionability**, and at 1 or better
on the rest; anything less is a fail with the failing dimension named.

| Dimension | 0 | 1 | 2 |
|:--|:--|:--|:--|
| **Grounding** | Cites a rule that does not exist, or none | Cites a real file but not the clause | Cites `<file>#<rule>` and the clause says what the finding claims |
| **Actionability** | "Consider revisiting" | A direction without a site | The smallest concrete edit, at a named site |
| **Scope discipline** | Findings outside the diff | Mostly in scope, one drift | Every finding is about what changed |
| **Honesty** | Reports a check it did not run as passing | Silent about an unrun check | Names it, with `not-run` and why |
| **Proportion** | Blocks on taste, or waves through a hard-rule breach | Severity roughly right | Severity matches the rule's own level |
| **Restraint** | Restates a banned figure, or invents a number | Padding, no invention | Says "none" when there is nothing |

## Rules the judge follows

1. **Judge the answer, not the agreement.** A finding this rubric's author would not have made is
   still correct if the clause supports it.
2. **A missing finding is only a failure when the scenario asserts it.** `run.py`'s `min_findings`
   and field assertions carry that; the judge does not add expectations of its own.
3. **Prose quality is not scored.** Terse is fine. Only the six dimensions above count.
4. **The judge never scores its own repository's roles more kindly than another's.** If the case
   was authored in the same repository as the role, that is a reason for suspicion, not licence.
5. **A 0 on Honesty fails the case regardless of the other five.** A confident wrong answer is the
   failure this whole layer exists to catch; a wrong answer that says it is unsure is not.

## Recording a run

The report `run.py` writes carries the runtime, the model, the case ids and the failures. A rubric
score is stored alongside the deterministic result, never instead of it — so a later reader can
see which half of the grade was reproducible.
