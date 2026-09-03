#!/usr/bin/env bash
#
# Keeps docs/release/1.0-scope.md and docs/ROADMAP.md from drifting apart.
#
# The roadmap owns the analysis and carries a `**1.0 disposition:**` line per item. The register
# owns the state. Nothing connected them, which is how an item can be dispositioned and then never
# tracked -- JPMS module naming was called a GA blocker in the v0.8 readiness audit, appeared in no
# roadmap section afterwards, and surfaced again only because somebody remembered it.
#
# What this checks, precisely: the set of roadmap headings carrying a 1.0 disposition equals the set
# the register declares it accounts for, in its `roadmap-dispositions` comment block. Exact string
# equality, so the result is decidable -- a fuzzy title match would produce false failures, and a
# gate that cries wolf gets skipped, which is worse than no gate.
#
# What it does NOT check: whether a recorded state is CORRECT. Nothing can. The register's own
# rules put every state behind either a named check or the word `unverified`, and that is a human
# obligation, not an automatable one.
set -euo pipefail

# Byte collation, for sort and comm alike. Under a locale like pl_PL, `sort` orders by locale rules
# while `comm` assumes its inputs are ordered the same way -- it warns "input is not in sorted
# order" and its output stops being trustworthy. A drift gate that is right by luck is not a gate.
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROADMAP="$ROOT/docs/ROADMAP.md"
REGISTER="$ROOT/docs/release/1.0-scope.md"

for f in "$ROADMAP" "$REGISTER"; do
  [ -f "$f" ] || { echo "FAIL  missing $f"; exit 1; }
done

# Roadmap: a `### ` heading is claimed when a later line, before the next heading, carries the
# disposition marker.
# Two details decide whether this extraction is right, and both were wrong in the first version:
#
#   - the marker is anchored to the start of a line. Unanchored it also matches the prose in the
#     "Road to 1.0" preamble, which describes the section rather than dispositioning an item.
#   - the heading resets on ANY level, not just `### `. With `### ` only, that preamble match --
#     which sits under a `## ` -- was attributed to the previous unrelated `### ` section, inventing
#     a disposition for an item that has none and padding the count by one.
#
# Both symptoms were a single extra row that looked ordinary. The register was hand-fitted to the
# broken output and the gate passed, which is the failure mode a gate is supposed to be immune to.
awk '
  /^#+ /                    { h = "" }
  /^### /                   { h = substr($0, 5); sub(/[ \t]+$/, "", h); next }
  /^\*\*1\.0 disposition/  { if (h != "") { print h; h = "" } }
' "$ROADMAP" | sort -u > /tmp/scope-roadmap.$$

# Register: the canonical list inside the roadmap-dispositions comment.
awk '
  /roadmap-dispositions:/ { inblock = 1; next }
  inblock && /^-->/       { inblock = 0 }
  inblock && /^     - /   { print substr($0, 8) }
' "$REGISTER" | sort -u > /tmp/scope-register.$$

missing="$(comm -23 /tmp/scope-roadmap.$$ /tmp/scope-register.$$ || true)"
extra="$(comm -13 /tmp/scope-roadmap.$$ /tmp/scope-register.$$ || true)"
roadmap_count="$(wc -l < /tmp/scope-roadmap.$$ | tr -d ' ')"
rm -f /tmp/scope-roadmap.$$ /tmp/scope-register.$$

if [ "$roadmap_count" -eq 0 ]; then
  echo "FAIL  no 1.0 dispositions found in the roadmap — the extractor matched nothing, so this"
  echo "      run proves nothing about the register"
  exit 1
fi

status=0
if [ -n "$missing" ]; then
  echo "FAIL  dispositioned in the roadmap, absent from the register:"
  printf '        %s\n' "$missing"
  status=1
fi
if [ -n "$extra" ]; then
  echo "FAIL  listed in the register, no such roadmap heading (renamed or removed?):"
  printf '        %s\n' "$extra"
  status=1
fi

if [ "$status" -ne 0 ]; then
  echo
  echo "Add the row to docs/release/1.0-scope.md, or correct the heading it names."
  exit 1
fi

echo "ok    $roadmap_count roadmap 1.0 dispositions, all accounted for in the register"
