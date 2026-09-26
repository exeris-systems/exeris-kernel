# `.claude/` — generated adapters and provider configuration

This directory is **not** where project rules are authored. Per
[`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rules 2 and 7, the canonical semantic source is [`.agents/`](../.agents) and this directory adapts it
for Claude Code.

- `agents/` — **generated** from `.agents/agents/<name>/AGENT.md`. Edit the source.
- `skills/` — two kinds sharing one directory, which is why the link below is per skill and not
  per directory:
  - one **symlink per skill** into `.agents/skills/`. Nothing here stores a second copy; five of
    the six runtimes read `.agents/skills/` natively and Claude Code is the one that does not.
  - one **generated** `SKILL.md` per workflow, carrying `disable-model-invocation: true`. That is
    the same user-invoked `/name` behaviour the former `commands/` directory gave, with one
    adapter kind fewer.
- `settings.json` — provider-owned, with a **generated region**: the renderer writes only the
  `hooks` key and merges around everything else, so the file is yours and the region is the
  layer's. It is declared under `provider-owned` in
  [`.agents/manifest.yaml`](../.agents/manifest.yaml), because JSON has no comments to carry a
  marker.
- `settings.local.json` — provider-owned local configuration. Never semantic content.
- `worktrees/` — a legacy location for local git worktrees, kept git-ignored. New worktrees go
  outside the repository.

A change made in this directory is lost the next time the renderer runs. That is the one thing to
remember.

## Rendering and checking them

**This repository carries no renderer.** ADR-085 §C.11: two implementations of one schema is how
the schema stops being one. Both halves come from `exeris-systems/exeris-agents`, pinned — the
semantics vendored under `.agents/vendor/` and digest-verified, the tooling checked out in CI at
the ref `.github/workflows/guardrails.yml` names.

```bash
# with a checkout of exeris-systems/exeris-agents at the pinned ref
python3 <bundle>/tools/agents_render.py     --root .          # rewrite every adapter from .agents/
python3 <bundle>/tools/agents_render.py     --root . --check  # CI form: an adapter that differs is drift
python3 <bundle>/tools/agents_file_check.py --root .          # the schema itself: layout, frontmatter, hooks, pins
python3 <bundle>/tools/agents_bundle.py     verify --root .   # the vendored copy still matches its digest
```

On a checkout without symlink support — Windows without Developer Mode — add `--skills-copy`. The
manifest records that fallback under `degradations`, so it is a written trade-off rather than a
surprise.

Every generated file carries the do-not-edit marker naming the source it came from. The renderer
diffs the authored half of a profile byte-for-byte and appends the composition — skills, policies,
handoffs, response contract — under a marker, so an edit to either half is visible in `--check`.

## The L0 hooks

`settings.json`'s `hooks` block invokes one dispatcher and carries no patterns of its own; the
patterns live in [`.agents/hooks/hooks.yaml`](../.agents/hooks/hooks.yaml) and are read at runtime.
Two shapes: a **deny** for actions no policy permits, and a **record + gate** for actions a policy
permits with a consequence — the action is allowed, what happened is written down, and the *stop*
is blocked until the consequence has been discharged. Session state goes to `.agents-state/`,
git-ignored and keyed per session.

A stop gate establishes that a command ran. It never establishes that it was the right command, or
that it passed.

## Auto-memory

Persistent memory for sessions opened in this repository lives outside it, under the Claude client's
own per-project store. It is provider-owned and holds process feedback and user preferences only.
Project facts belong in `AGENTS.md`, in `.agents/`, or in the documents and records that own them —
versioned, and visible to humans and to other tools.
