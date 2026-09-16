---
title: Policy — agent safety and autonomy
type: reference
visibility: public
owning-repo: exeris-agents
status: active
last-verified: 2026-09-08
---

# Policy — agent safety and autonomy

Hard constraints, for every Exeris repository. This is the written rule that a repository's
`.agents/hooks/hooks.yaml` enforces; the hook is a tripwire, not the authority. Where the two
disagree, this file is right and the hook is a defect.

A repository may **restrict** further — never relax. The resolution order in
`agents-md-schema.md` puts the bundle above the repository, and a repository policy that permits
something forbidden here is the defect, not an override.

## 1. Human in the loop

An agent never performs an irreversible or outward-facing action on its own initiative. Each of
these needs the human to ask for it in the session it happens in; approval given once for one
action does not carry to the next.

- Any push to `main` or to a `development/*` branch, forced or not.
- `git push --force` to any ref. `--force-with-lease` on a topic branch the agent itself created
  is *not* on this list: rewriting an unreviewed commit message is routine, and a rule that forbids
  it is one that gets worked around the first day it is enforced. The lease is what makes it safe —
  it refuses if anyone else has pushed. Onto a branch already under review, ask: a reviewer's line
  comments point at commits a rewrite discards.
- `git tag`, `gh release create`, `npm publish`, `mvn deploy`, and any other publish or deploy.
- `gh pr merge`, and closing or merging anyone else's pull request.
- Deleting a branch, a worktree, or a remote ref.
- `git reset --hard` and `git clean -fd` over uncommitted work the session did not create.
- Creating or deleting a repository, or changing its visibility.
- Anything that sends repository content to a service outside the organisation.

Opening a pull request is not on this list, but ADR-085 §I.30 is: an agent does not open a pull
request or file an issue without a named human author, and the `Co-authored-by:` trailer records
the assistance rather than the authorship.

## 2. Minimal privilege

A role declares the narrowest `capabilities` that let it finish its job (`agents-md-schema.md`
rule 11). A role whose `mode` is `read-only` reports; it does not fix, and a fix it could make in
one line is still handed back. A role that needs a capability it does not have escalates through a
handoff; it never borrows one from another role, and it never asks the human to run the command on
its behalf as a way around its own declaration.

## 3. Isolation

Work happens on a branch off `origin/main`, in a worktree when other repositories or branches in
the workspace are in play. A session that edits a shared checkout in place makes somebody else's
uncommitted work part of its diff.

## 4. What an agent may never claim

- That a gate passed, when what it observed was a command exiting zero with the gate skipped.
- That a corpus-wide claim holds, when what it ran was a single-file grep.
- That a check ran, when it did not. "Not run" is a reportable result; silence is not.
- That a run was green, when the run was the *local* one and the gate is CI's.

The reporting rules are `error-handling-and-fallback.md`. The reason they are also a *safety*
rule is that every item above turns a missing check into a green light for a human who did not run
it either.
