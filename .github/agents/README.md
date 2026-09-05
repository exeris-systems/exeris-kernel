<!-- Every sibling file in this directory is generated from .agents/ and carries its own
     do-not-edit marker. This README is hand-written; the banner is here because
     agents_file_check.py requires the marker on every .md under a provider semantic directory,
     and an accidental match in the prose is not a guarantee. -->
# `.github/agents/` — generated Copilot adapters

Not a place to author rules. These files are **generated** from [`.agents/agents/`](../../.agents/agents),
the canonical role profiles, and each carries a do-not-edit marker naming its source
([`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rules 2 and 7). Edit the source; a change made here is lost at the next regeneration.

Routing between the profiles is the router profile's own job — it is described once, in
[`.agents/agents/exeris-router.md`](../../.agents/agents/exeris-router.md), not duplicated here.
Rewrite them with `tools/agent-adapter-check/agent-adapter-render.sh` and verify with
`…/agent-adapter-check.sh`; see [`.claude/README.md`](../../.claude/README.md) for the mechanics,
which are the same for both providers.
