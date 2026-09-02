#!/usr/bin/env bash
#
# Keeps the EX- error-code registry and its operator-facing documentation in agreement.
#
# WHY THIS IS A GATE, and why it is three checks rather than one.
#
# KernelErrorCodes is declared the single source of truth for error codes (CLAUDE.md, hard
# constraint) and docs/subsystems/exceptions.md is what an operator actually reads when a code turns
# up in a log. Nothing connected the two. The drift is silent in both directions and each direction
# fails differently:
#
#   - A code in the registry with no doc row reaches an operator as an identifier nothing explains.
#     Measured at the time this gate was written: TWENTY of 84 codes, including four entire domains
#     (EX-BLOB, EX-DIAG, EX-JOB and the EX-UNK sentinel). It was found by chasing ONE missing row
#     and looking at the rest, not by anything failing.
#   - A doc row with no code in the registry is a promise nothing keeps — an operator greps for a
#     code that cannot be emitted. The v0.8 documentation sweep found five of those, so this
#     direction is not hypothetical either.
#
# The third check is different in kind and belongs here because it has the same root: an EX- code
# written as a STRING LITERAL outside the registry is a second source of truth that no rename will
# follow. Two existed when this gate was written — a JFR event stamping "EX-MEM-1003" by hand, and a
# telemetry sink whose "EX-UNK-0000" fallback was not a registered code at all, so every record with
# no code of its own was stamped with an identifier absent from the registry AND from the doc.
#
# NOT CHECKED, deliberately: whether every code has a thrower. It looks like the natural fourth
# check and it would be wrong — several codes are emitted by an orchestrator or a JFR event rather
# than by an exception constructor (EX-BOOT-0001 says so in its own javadoc, EX-DIAG-* are audit
# records and not exceptions at all, EX-JOB-9001 and EX-JOB-9003 are JFR-only because a dispatched
# job has no caller to throw to). A thrower check would report all of those as dead and teach
# everyone to ignore it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REGISTRY="$ROOT/exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/exceptions/KernelErrorCodes.java"
DOC="$ROOT/docs/subsystems/exceptions.md"

[ -f "$REGISTRY" ] || { echo "error-code-registry-check: registry not found: $REGISTRY" >&2; exit 1; }
[ -f "$DOC" ]      || { echo "error-code-registry-check: doc not found: $DOC" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# LC_ALL=C throughout: under a non-C locale `sort` and `comm` disagree about ordering and `comm`
# silently produces nonsense rather than failing.
export LC_ALL=C

# Registry side: only the declaration form, so a code MENTIONED in a javadoc {@link} or a retirement
# comment is not mistaken for a declared one.
grep -oE '^    public static final String EX_[A-Z]+_[0-9]+ = "EX-[A-Z]+-[0-9]+";$' "$REGISTRY" \
  | grep -oE '"EX-[A-Z]+-[0-9]+"' | tr -d '"' | sort -u > "$work/registry.txt" || true

# Doc side: only a table row's leading cell, so a code named in prose does not count as documented.
grep -oE '^\| `EX-[A-Z]+-[0-9]+`' "$DOC" | grep -oE 'EX-[A-Z]+-[0-9]+' | sort -u > "$work/doc.txt" || true

registry_count=$(wc -l < "$work/registry.txt")
doc_count=$(wc -l < "$work/doc.txt")

# A gate that cannot fail is worse than no gate: if either extractor silently matched nothing, every
# comparison below trivially passes. Refuse instead.
#
# The `|| true` on both extractions above is what makes this guard REACHABLE. Without it, `grep`
# returning 1 on no-match combines with `pipefail` and `set -e` to kill the script here, and the
# gate exits non-zero with no message at all — the right exit code for the wrong reason, and
# nothing on screen telling anyone the format moved. Measured: renaming the doc's table rows
# produced exit 1 and zero output until this was fixed.
if [ "$registry_count" -eq 0 ] || [ "$doc_count" -eq 0 ]; then
  echo "error-code-registry-check: FAIL — an extractor matched nothing (registry=$registry_count, doc=$doc_count)." >&2
  echo "  The file format changed under the gate. Fix the pattern; do not assume the check passed." >&2
  exit 1
fi

failures=0

undocumented=$(comm -23 "$work/registry.txt" "$work/doc.txt")
if [ -n "$undocumented" ]; then
  echo "error-code-registry-check: FAIL — declared in KernelErrorCodes, absent from exceptions.md:" >&2
  echo "$undocumented" | sed 's/^/  /' >&2
  echo "  Add a table row under the matching domain section, with the rawArgs layout from the javadoc." >&2
  failures=$((failures + 1))
fi

unregistered=$(comm -13 "$work/registry.txt" "$work/doc.txt")
if [ -n "$unregistered" ]; then
  echo "error-code-registry-check: FAIL — documented in exceptions.md, absent from KernelErrorCodes:" >&2
  echo "$unregistered" | sed 's/^/  /' >&2
  echo "  Either the code was removed and the row should go, or the row names a code nobody can emit." >&2
  failures=$((failures + 1))
fi

# Constant name and its value must agree. Cheap, and the failure it catches (a copy-pasted
# declaration whose value still names the code above it) is invisible at every other layer: the
# build is fine, the doc row exists, and the wrong code ships.
mismatched=$(grep -oE 'EX_[A-Z]+_[0-9]+ = "EX-[A-Z]+-[0-9]+"' "$REGISTRY" \
  | awk -F' = ' '{ name = $1; gsub(/_/, "-", name); value = $2; gsub(/"/, "", value);
                   if (name != value) print "  " $0 }')
if [ -n "$mismatched" ]; then
  echo "error-code-registry-check: FAIL — constant name does not match its value:" >&2
  echo "$mismatched" >&2
  failures=$((failures + 1))
fi

# An EX- code as a string literal in main sources, outside the registry. Comments and javadoc are
# stripped first: several classes legitimately cite a code in prose ("e.g. \"EX-NET-2002\"") and the
# usage examples in ExerisKernelException's own javadoc are documentation, not a second definition.
# Test sources are out of scope — a fixture asserting on an unknown code is exercising the sink's
# fallback, which is the point of the fixture.
leaked=$(find "$ROOT" -path '*/src/main/java/*' -name '*.java' ! -path '*/target/*' \
           ! -name 'KernelErrorCodes.java' -print0 \
         | xargs -0 python3 -c '
import re, sys
strip = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    for line_no, line in enumerate(strip.sub(lambda m: "\n" * m.group(0).count("\n"), source).splitlines(), 1):
        for hit in re.findall(r"\"EX-[A-Z]+-[0-9]+\"", line):
            print("  %s:%d  %s" % (path, line_no, hit))
')
if [ -n "$leaked" ]; then
  echo "error-code-registry-check: FAIL — EX- code written as a literal instead of a KernelErrorCodes constant:" >&2
  echo "$leaked" | sed "s|$ROOT/||" >&2
  echo "  KernelErrorCodes is the single source of truth (CLAUDE.md); a literal survives a rename it should not." >&2
  failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
  echo "error-code-registry-check: $failures check(s) failed." >&2
  exit 1
fi

echo "error-code-registry-check: $registry_count codes, each documented; no unregistered rows, no literals."
