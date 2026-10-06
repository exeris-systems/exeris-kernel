#!/usr/bin/env bash
#
# Fails when a script under tools/ answers --help with anything other than its whole header.
#
# A script's header is its documentation: the comment block from line 2 up to the first line that is
# not a comment. --help is how anyone reads it without opening the file, so --help has to print
# exactly that block, exit 0, and keep doing so when the header grows or shrinks. A help handler
# that prints a fixed line range satisfies the first two and silently fails the third.
#
# Which scripts are checked: every tools/**/*.sh that handles `--help`, found by searching for the
# handler rather than listed by hand, so a new gate is covered from its first commit. A run that
# finds none fails, because a check that inspected nothing would otherwise report success.
#
# Usage:
#   tools/help-header-check/help-header-check.sh
#
# Needs no build: each script answers --help before it looks for anything it checks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

case "${1:-}" in
  -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
  "") ;;
  *) echo "unknown argument: $1" >&2; exit 2 ;;
esac

mapfile -t scripts < <(grep -rlE --include='*.sh' -e '-h\|--help\)' tools | LC_ALL=C sort)
if [ "${#scripts[@]}" -eq 0 ]; then
  echo "help-header-check: FAILED — no script under tools/ handles --help; nothing was checked" >&2
  exit 1
fi

failed=0
for script in "${scripts[@]}"; do
  expected="$(awk 'NR > 1 && !/^#/ { exit } NR > 1' "$script")"
  if ! actual="$(bash "$script" --help 2>&1)"; then
    echo "help-header-check: $script --help exited non-zero" >&2
    failed=1
    continue
  fi
  if [ "$actual" != "$expected" ]; then
    echo "help-header-check: $script --help does not print its whole header" >&2
    echo "  header: $(printf '%s\n' "$expected" | wc -l) line(s), ends: $(printf '%s\n' "$expected" | tail -1)" >&2
    echo "  --help: $(printf '%s\n' "$actual" | wc -l) line(s), ends: $(printf '%s\n' "$actual" | tail -1)" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "help-header-check: OK — ${#scripts[@]} script(s), each --help prints its whole header"
