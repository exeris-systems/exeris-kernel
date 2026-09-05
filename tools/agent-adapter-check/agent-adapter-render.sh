#!/usr/bin/env bash
#
# Agent-adapter renderer (ADR-085 §I.29, agents-md-schema.md rules 2 and 7).
#
# Rewrites every provider adapter from its canonical source under `.agents/`, preserving the
# destination's own frontmatter — Claude and Copilot name and configure the same profile
# differently, and that difference is provider configuration, not semantics.
#
# The marker is written twice, and both placements are load-bearing:
#   * a YAML comment as the first frontmatter line, because the organisation gate
#     (`agents_file_check.py`) reads only the first 600 characters when it looks for one, and
#     Copilot's `tools:` list alone is longer than that;
#   * an HTML comment below the frontmatter, which is what a human sees on opening the file.
#
# Run it after editing anything under `.agents/`, then verify with the sibling script:
#   tools/agent-adapter-render.sh && tools/agent-adapter-check/agent-adapter-check.sh
#
# Adding a NEW skill, profile or workflow needs a destination frontmatter block for each provider;
# this script will not invent one, and reports what is missing instead.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

python3 - <<'PY'
import pathlib, sys

YAML_MARK = "# DO NOT EDIT — generated from {src} (agents-md-schema.md rule 7). Edit the source.\n"
HTML_MARK = ("<!-- DO NOT EDIT. Generated from {src} by the AGENTS.md adapter step\n"
             "     (agents-md-schema.md rule 7). Edit the source, not this file. -->\n")

MAP = {  # canonical kind -> (claude path template, copilot path template)
    "skills":    (".claude/skills/{n}/SKILL.md", ".github/skills/{n}/SKILL.md"),
    "agents":    (".claude/agents/{n}.md",       ".github/agents/{n}.agent.md"),
    "workflows": (".claude/commands/{n}.md",     ".github/prompts/{n}.prompt.md"),
}
EXTRA = {".agents/policies/sonarqube-mcp.md": [".github/instructions/sonarqube_mcp.instructions.md"]}

def split_fm(text):
    if not text.startswith("---\n"):
        return None, text
    end = text.index("\n---", 3) + len("\n---\n")
    return text[:end], text[end:].lstrip("\n")

def strip_marker(fm):
    return "".join(l for l in fm.splitlines(keepends=True) if "DO NOT EDIT" not in l)

def render(src: pathlib.Path, dst: pathlib.Path, missing: list):
    if not dst.exists():
        missing.append(f"{dst} has no provider frontmatter yet — create it once, then rerun")
        return 0
    sfm, body = split_fm(src.read_text(encoding="utf-8"))
    if sfm is None:                      # a policy rendered as an instructions file
        body = src.read_text(encoding="utf-8")
    dfm, _ = split_fm(dst.read_text(encoding="utf-8"))
    dfm = strip_marker(dfm) if dfm else "---\n---\n"
    out = ("---\n" + YAML_MARK.format(src=src.as_posix()) + dfm[len("---\n"):]
           + HTML_MARK.format(src=src.as_posix()) + body)
    before = dst.read_text(encoding="utf-8")
    dst.write_text(out, encoding="utf-8")
    return int(out != before)

changed, missing = 0, []
for kind, (claude_t, copilot_t) in MAP.items():
    base = pathlib.Path(".agents") / kind
    if not base.is_dir():
        continue
    names = ([d.name for d in sorted(base.iterdir()) if d.is_dir()] if kind == "skills"
             else [f.stem for f in sorted(base.glob("*.md"))])
    for n in names:
        src = base / (f"{n}/SKILL.md" if kind == "skills" else f"{n}.md")
        for t in (claude_t, copilot_t):
            changed += render(src, pathlib.Path(t.format(n=n)), missing)
for s, dsts in EXTRA.items():
    for d in dsts:
        changed += render(pathlib.Path(s), pathlib.Path(d), missing)

print(f"agent-adapter-render: {changed} adapter(s) rewritten")
for m in missing:
    print(f"  SKIP  {m}", file=sys.stderr)
sys.exit(1 if missing else 0)
PY
