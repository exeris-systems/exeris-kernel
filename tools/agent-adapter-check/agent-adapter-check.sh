#!/usr/bin/env bash
#
# Agent-adapter fidelity gate (ADR-085 §I.29, agents-md-schema.md rules 2 and 7).
#
# `.agents/` is the canonical semantic source; `.claude/` and `.github/{agents,prompts,skills,
# instructions}` are rendered from it. There is no renderer yet, so the copies are refreshed by
# hand — which is exactly the situation where two of the three drift apart and every one of them
# still looks correct.
#
# The organisation gate (`agents_file_check.py` in exeris-systems/.github) checks that a generated
# file SAYS it is generated. It cannot check that the file still MATCHES what generated it, because
# it does not know the pairing. This script does, and that is the whole reason it exists: a
# cherry-pick has already put one branch's text into another branch's canonical source while both
# adapters kept a valid marker.
#
# Two assertions per generated file:
#   1. body identity   — everything below the frontmatter and the marker block is byte-identical
#                        to the source named in the marker;
#   2. marker window   — the marker appears within the first 600 characters, which is all the
#                        organisation gate reads. Copilot's `tools:` list alone runs past that, so
#                        a marker placed only after the frontmatter can silently fall out of range.
#
# README.md files under a provider directory are checked too, and deliberately so: the organisation
# gate requires the marker on EVERY .md there, so a hand-written README needs a real banner rather
# than a phrase that happens to match a regex.
#
# Usage:
#   tools/agent-adapter-check/agent-adapter-check.sh          # from the repository root
#
# Exit 0 clean, 1 on any mismatch. No build required.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

python3 - "$@" <<'PY'
import pathlib, re, sys

MARKER_WINDOW = 600
GENERATED = re.compile(r"do[- ]not[- ]edit|generated from|@generated", re.I)
SOURCE = re.compile(r"generated from (\S+)")
ROOTS = [".claude/skills", ".claude/agents", ".claude/commands",
         ".github/skills", ".github/agents", ".github/prompts", ".github/instructions"]
# Provider-owned operational documentation: not rendered from anything.
EXEMPT = {".github/branch-protection.md"}

def body(text: str) -> str:
    """Everything below the YAML frontmatter and any leading marker comment."""
    if text.startswith("---\n"):
        text = text[text.index("\n---", 3) + len("\n---\n"):]
    text = re.sub(r"\A<!--.*?-->\n", "", text.lstrip("\n"), flags=re.S)
    return text.lstrip("\n")

errors, checked = [], 0
for root in ROOTS:
    d = pathlib.Path(root)
    if not d.is_dir():
        continue
    for p in sorted(d.rglob("*.md")):
        rel = p.as_posix()
        if rel in EXEMPT:
            continue
        text = p.read_text(encoding="utf-8")
        checked += 1
        if not GENERATED.search(text[:MARKER_WINDOW]):
            errors.append(f"{rel}: no generated-from marker in the first {MARKER_WINDOW} characters")
            continue
        if p.name == "README.md":
            continue                      # hand-written; banner checked above, no source to match
        m = SOURCE.search(text)
        if not m:
            errors.append(f"{rel}: marker names no source path")
            continue
        src = pathlib.Path(m.group(1))
        if not src.exists():
            errors.append(f"{rel}: source {src} does not exist")
            continue
        if body(text) != body(src.read_text(encoding="utf-8")):
            errors.append(f"{rel}: body differs from {src} — regenerate, do not edit the adapter")

print(f"agent-adapter-check: {checked} provider files checked against .agents/")
for e in errors:
    print(f"  FAIL  {e}", file=sys.stderr)
sys.exit(1 if errors else 0)
PY
