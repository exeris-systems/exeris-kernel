#!/usr/bin/env bash
# Refuses a TLS matrix run whose suites skipped or ran nothing.
#
# A skipped test reports green, and so does a -Dtest pattern that matched nothing. Each row of the
# floors file names a class and the fewest tests it must have executed (tests minus skipped); a row
# marked zero-skip additionally refuses any skipped test. A class with no report fails.
#
# A row that cannot be read as a floor fails the run: a gate that ignores what it cannot parse
# passes on exactly the rows nobody checked.
#
# Floors file, one row per line, '#' starts a comment:
#   <report-dir> <class> <min-executed> [zero-skip]
#   total <min-executed>          (floor on the sum of every row's executed tests)
#
# Usage: bash tls-matrix-floor.sh <floors-file> [<root-dir>]
# Report directories are resolved against <root-dir> (default: the current directory). The script is
# run through bash, never executed directly, so it does not depend on its file mode.
set -euo pipefail
shopt -s nullglob

floors_file=${1:?usage: bash tls-matrix-floor.sh <floors-file> [<root-dir>]}
root=${2:-.}
failed=0
sum=0
total_floor=""

# Prints "<tests> <skipped>" summed over the class's report and its nested-class reports.
count_class() {
  local dir=$1 cls=$2 tests=0 skipped=0 file head t s
  local files=("$dir"/TEST-*."$cls".xml "$dir"/TEST-*."$cls"'$'*.xml)
  if [ ${#files[@]} -eq 0 ]; then
    return 1
  fi
  for file in "${files[@]}"; do
    head=$(grep -m1 -o '<testsuite [^>]*' "$file" || true)
    t=$(grep -o ' tests="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    s=$(grep -o ' skipped="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    tests=$((tests + ${t:-0}))
    skipped=$((skipped + ${s:-0}))
  done
  echo "$tests $skipped"
}

while read -r dir cls min flag _; do
  case "${dir:-}" in
    ''|'#'*) continue ;;
    total)
      if [[ ! ${cls:-} =~ ^[0-9]+$ ]] || [ -n "${min:-}" ]; then
        echo "::error::malformed total row: 'total <min-executed>' expected"
        failed=1
      else
        total_floor=$cls
      fi
      continue ;;
  esac
  if [[ ! ${cls:-} =~ ^[A-Za-z0-9_]+$ ]] || [[ ! ${min:-} =~ ^[0-9]+$ ]] \
      || { [ -n "${flag:-}" ] && [ "$flag" != "zero-skip" ]; }; then
    echo "::error::malformed floors row: '$dir ${cls:-} ${min:-} ${flag:-}' (want <report-dir> <class> <min-executed> [zero-skip])"
    failed=1
    continue
  fi
  if ! counts=$(count_class "$root/$dir" "$cls"); then
    echo "::error::$cls: no report in $dir"
    failed=1
    continue
  fi
  read -r tests skipped <<<"$counts"
  executed=$((tests - skipped))
  sum=$((sum + executed))
  echo "$cls: tests=$tests skipped=$skipped executed=$executed floor=$min"
  if [ "$executed" -lt "$min" ]; then
    echo "::error::$cls executed $executed tests, below its floor of $min"
    failed=1
  fi
  if [ "${flag:-}" = "zero-skip" ] && [ "$skipped" -gt 0 ]; then
    echo "::error::$cls skipped $skipped tests; it must skip none"
    failed=1
  fi
done < "$floors_file"

if [ -n "$total_floor" ]; then
  echo "total executed=$sum floor=$total_floor"
  if [ "$sum" -lt "$total_floor" ]; then
    echo "::error::the matrix executed $sum tests in total, below its floor of $total_floor"
    failed=1
  fi
fi
exit "$failed"
