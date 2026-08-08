#!/usr/bin/env bash
#
# Preview-bytecode gate (ADR-066).
#
# Asserts that nothing the project DISTRIBUTES carries preview bytecode, and that every shipped
# class targets the declared LTS class-file major. Test and TCK fixtures are deliberately out of
# scope: they still use StructuredTaskScope, they compile with --enable-preview, and they are not
# published.
#
# Why bytecode rather than a source grep: `--enable-preview` stamps a class with
# minor_version 0xFFFF, and the JVM then refuses to load it on any other major release EVEN WITH
# the flag. That stamp is the thing a consumer actually trips over, so it is the thing the gate
# reads. A source-level check would also miss a preview construct reached through a dependency's
# annotation processor or through generated code.
#
# Usage:
#   tools/preview-bytecode-scan/preview-bytecode-scan.sh [--expect-major N]
#
# Requires the reactor to have been built first (reads */target/classes).
set -euo pipefail

EXPECT_MAJOR=69   # JDK 25 LTS

while [ $# -gt 0 ]; do
  case "$1" in
    --expect-major) EXPECT_MAJOR="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

python3 - "$EXPECT_MAJOR" <<'PY'
import pathlib, struct, sys

expect_major = int(sys.argv[1])
scanned = 0
preview = []
wrong_major = []

for path in pathlib.Path('.').glob('*/target/classes/**/*.class'):
    raw = path.read_bytes()
    if len(raw) < 8 or raw[:4] != b'\xca\xfe\xba\xbe':
        continue
    minor, major = struct.unpack('>HH', raw[4:8])
    scanned += 1
    if minor == 0xFFFF:
        preview.append(str(path))
    if major != expect_major:
        wrong_major.append((str(path), major))

if scanned == 0:
    print("preview-bytecode gate: FAILED — scanned 0 classes; build the reactor first")
    sys.exit(1)

print(f"preview-bytecode gate: scanned {scanned} distributed classes "
      f"(expecting class-file major {expect_major}, no preview stamp)")

failed = False

if preview:
    failed = True
    print(f"\nFAILED — {len(preview)} distributed class(es) carry preview bytecode "
          f"(minor_version 0xFFFF).")
    print("A consumer of these would need --enable-preview across their ENTIRE build, and the "
          "classes would refuse to load on any JDK other than the exact one that produced them.")
    for p in preview[:25]:
        print(f"    {p}")
    if len(preview) > 25:
        print(f"    ... and {len(preview) - 25} more")

if wrong_major:
    failed = True
    seen = sorted({m for _, m in wrong_major})
    print(f"\nFAILED — {len(wrong_major)} distributed class(es) target class-file major {seen}, "
          f"not {expect_major}.")
    print("A higher major than the declared LTS baseline cannot be loaded by that LTS at all, "
          "which is the same consumer-facing break as the preview stamp by a different route.")
    for p, m in wrong_major[:25]:
        print(f"    major {m}  {p}")
    if len(wrong_major) > 25:
        print(f"    ... and {len(wrong_major) - 25} more")

if failed:
    sys.exit(1)

print("preview-bytecode gate: PASSED")
PY
