#!/usr/bin/env bash
# Proves tls-matrix-floor.sh fails closed: each case states the exit status the script must return
# for a floors file run against the fixture reports in fixtures/reports (Foo executes 7 tests,
# Bar executes 2 and skips none).
#
# Usage: bash .github/scripts/tls-matrix-floor-selftest.sh
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
fixtures="$here/fixtures"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failed=0

# expect <name> <expected-exit> <floors-file-content>
expect() {
  local name=$1 want=$2 content=$3 got
  printf '%b' "$content" > "$work/floors.txt"
  bash "$here/tls-matrix-floor.sh" "$work/floors.txt" "$fixtures" >/dev/null 2>&1
  got=$?
  if [ "$got" -eq "$want" ]; then
    echo "ok   $name: exit $got"
  else
    echo "FAIL $name: exit $got, wanted $want"
    failed=1
  fi
}

expect "matching floors" 0 'reports Foo 7\nreports Bar 2 zero-skip\ntotal 9\n'
expect "floor raised by one" 1 'reports Foo 8\nreports Bar 2 zero-skip\ntotal 9\n'
expect "class with no report" 1 'reports Foo 7\nreports Baz 1\n'
expect "skip under zero-skip" 1 'reports Foo 7 zero-skip\nreports Bar 2\n'
expect "total raised by one" 1 'reports Foo 7\nreports Bar 2\ntotal 10\n'
expect "malformed floor" 1 'reports Foo 7x\n'
expect "missing floor" 1 'reports Foo\n'
expect "unknown flag" 1 'reports Foo 7 zero_skip\n'
expect "malformed total" 1 'reports Foo 7\ntotal nine\n'
expect "total with no floor" 1 'reports Foo 7\ntotal\n'
exit "$failed"
