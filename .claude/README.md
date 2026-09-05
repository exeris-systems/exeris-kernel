# `.claude/` — generated adapters and provider configuration

This directory is **not** where project rules are authored. Per
[`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rules 2 and 7, the canonical semantic source is [`.agents/`](../.agents) and this directory adapts it
for Claude Code. The same is true of `.github/agents`, `.github/prompts` and `.github/skills`, which
adapt the same source for Copilot.

- `skills/`, `agents/`, `commands/` — **generated** from `.agents/skills`, `.agents/agents` and
  `.agents/workflows`. Edit the source.
- `settings.local.json` — provider-owned local configuration. Never semantic content.

There is no renderer yet, so the adapters are refreshed by hand when their source changes. That is
the one thing to remember: a change made here is lost the next time they are regenerated.

## How the adapters are marked

Every generated file carries the do-not-edit marker **twice**, and both are load-bearing:

- a YAML comment as the first line inside the frontmatter, because `agents_file_check.py` reads only
  the first 600 characters of a provider file when it looks for the marker. A long `description` —
  or Copilot's `tools:` list, which alone runs past 600 — would push an after-frontmatter marker out
  of that window while the file still looked perfectly correct;
- an HTML comment below the frontmatter, which is what a human sees on opening the file.

After regenerating by hand, check both provider sets:

```bash
python3 -c "
import pathlib
for r in ['.claude/skills','.claude/agents','.claude/commands','.github/skills','.github/agents','.github/prompts']:
    for p in pathlib.Path(r).rglob('*.md'):
        if p.name != 'README.md' and 'do not edit' not in p.read_text(encoding='utf-8')[:600].lower():
            print('unmarked or marker too late:', p)
"
```

Silence means every adapter passes.

## Auto-memory

Persistent memory for sessions opened in this repository lives outside it, under
`~/.claude/projects/-home-arkstack-exeris-systems-exeris-kernel/memory/`. It is provider-owned and
holds process feedback and user preferences only. Project facts belong in `AGENTS.md`, in
`.agents/`, or in the documents and records that own them — versioned, and visible to humans and to
other tools.
