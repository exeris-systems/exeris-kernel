#!/usr/bin/env bash
#
# Asserts that NativeSmokeIT and NativeSmokeKafkaIT generated complete failsafe reports.
#
# Mirrors the check pattern in .github/workflows/maven.yml ("Refuse skipped or empty TLS suites"):
# requires that the Failsafe report XML files exist, show exactly the expected number of tests
# (6 for community, 1 for kafka), and show 0 skipped, 0 failures, and 0 errors.
#
# Usage:
#   tools/native-smoke/check-reports.sh
#
# Exit status:
#   0 when all reports match expected test counts with zero skips/failures/errors
#   1 if any report is missing, skipped, or failed
set -euo pipefail
shopt -s nullglob

case "${1:-}" in
  -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

failed=0

require_suite() {
  local dir=$1 cls=$2 expected_count=$3 tests=0 skipped=0 failures=0 errors=0 file head t s f e
  local files=("$dir"/TEST-*."$cls".xml "$dir"/TEST-*."$cls"'$'*.xml)
  if [ ${#files[@]} -eq 0 ]; then
    echo "::error::$cls: no report found in $dir" >&2
    failed=1
    return
  fi
  for file in "${files[@]}"; do
    head=$(grep -m1 -o '<testsuite [^>]*' "$file" || true)
    t=$(grep -o ' tests="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    s=$(grep -o ' skipped="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    f=$(grep -o ' failures="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    e=$(grep -o ' errors="[0-9]*"' <<<"$head" | tr -dc '0-9' || true)
    tests=$((tests + ${t:-0}))
    skipped=$((skipped + ${s:-0}))
    failures=$((failures + ${f:-0}))
    errors=$((errors + ${e:-0}))
  done
  echo "$cls: tests=$tests skipped=$skipped failures=$failures errors=$errors (expected tests=$expected_count)"
  if [ "$tests" -ne "$expected_count" ] || [ "$skipped" -ne 0 ] || [ "$failures" -ne 0 ] || [ "$errors" -ne 0 ]; then
    echo "::error::$cls failed report criteria: tests=$tests (expected $expected_count), skipped=$skipped, failures=$failures, errors=$errors" >&2
    failed=1
  fi
}

require_suite "exeris-kernel-native-smoke/target/failsafe-reports" "NativeSmokeIT" 6
require_suite "exeris-kernel-native-smoke-kafka/target/failsafe-reports" "NativeSmokeKafkaIT" 1

exit "$failed"
