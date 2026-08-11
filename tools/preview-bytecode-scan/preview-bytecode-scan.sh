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
# Requires a FULL reactor build first (`mvn install` / `mvn package`): the gate reads the published
# jars, and refuses to run if any module that should have produced one has not.
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
import pathlib, struct, sys, xml.etree.ElementTree as ET, zipfile

expect_major = int(sys.argv[1])
scanned = 0
preview = []
wrong_major = []


POM_NS = '{http://maven.apache.org/POM/4.0.0}'


def owned_by_us(entry):
    """Whether this class is one WE author, as opposed to a vendored dependency.

    A path-literal `startswith('eu/exeris/')` gets two cases wrong, and both are ours: a
    multi-release jar files our classes under `META-INF/versions/<N>/`, and a `module-info.class`
    sits at the jar root with no package prefix at all. Missing either means the class-file-major
    check silently skips exactly the classes most likely to have come from a different compiler.

    The two rules must not be combined naively, though. A VERSIONED `module-info.class` belongs to
    whoever owns the overlay, and in the shaded diagnostics CLI that is a vendored dependency: this
    build carries two at major 53, which are correct for their owner and none of our business.
    Ours would be unversioned, at the jar root.
    """
    if entry == 'module-info.class':
        return True
    if entry.startswith('META-INF/versions/'):
        parts = entry.split('/', 3)
        return len(parts) == 4 and parts[2].isdigit() and parts[3].startswith('eu/exeris/')
    return entry.startswith('eu/exeris/')


def inspect(name, entry, raw):
    global scanned
    if len(raw) < 8 or raw[:4] != b'\xca\xfe\xba\xbe':
        return
    minor, major = struct.unpack('>HH', raw[4:8])
    scanned += 1
    # Preview stamp: checked on EVERY class in the jar. A stamped third-party class would break a
    # consumer exactly as one of ours would, and the uber-jar CLI bundles its dependencies.
    if minor == 0xFFFF:
        preview.append(name)
    # Class-file major: only on classes this project authors. A vendored dependency compiled for an
    # older release is normal — the shaded diagnostics CLI carries slf4j at major 52 — and holding it
    # to our baseline would fail the gate on someone else's build choice.
    if owned_by_us(entry) and major != expect_major:
        wrong_major.append((name, major))


def publishing_modules():
    """Modules the reactor DECLARES, minus the pom-packaged ones that publish no jar.

    Derived from the reactor rather than from what is on disk. A disk glob answers "what did this
    build happen to produce", which is the same question in a full build and a very different one
    after `mvn -pl <one-module> package` — and in that case it answers "everything I found was
    clean" having looked at a single jar. The failure mode is silent and reads as success, which is
    the property no gate may have.
    """
    root = ET.parse('pom.xml').getroot()
    names = [m.text.strip() for m in root.iter(POM_NS + 'module')]
    expected = []
    for name in names:
        module_pom = pathlib.Path(name) / 'pom.xml'
        if not module_pom.is_file():
            continue
        packaging = ET.parse(module_pom).getroot().findtext(POM_NS + 'packaging', 'jar').strip()
        if packaging != 'pom':
            expected.append(name)
    return expected


# Read the JARS, not a directory glob. The scope of this gate has to be derived from what the build
# actually publishes, because the thing it is protecting against is a consumer tripping over a stamp
# in a downloaded artifact. A `*/target/classes/**` glob looked equivalent and was not:
# exeris-kernel-tck has no src/main at all, so its entire distributed surface is a test-jar built
# from src/test — 55 of its classes shipped preview-stamped, invisible to this gate by construction,
# for the whole milestone that advertised the opposite.
expected_modules = publishing_modules()
missing = []
for module in expected_modules:
    jars = [j for j in sorted((pathlib.Path(module) / 'target').glob('*.jar'))
            if not j.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if not jars:
        missing.append(module)
        continue
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as zf:
                for entry in zf.namelist():
                    if entry.endswith('.class'):
                        inspect(f"{jar}!{entry}", entry, zf.read(entry))
        except (zipfile.BadZipFile, OSError) as e:
            # An unreadable artifact is an unscanned artifact. Letting the traceback out would end
            # the run with a non-zero exit and no statement of WHAT was not checked.
            print(f"preview-bytecode gate: FAILED — cannot read {jar}: {e}")
            sys.exit(1)

if missing:
    print(f"preview-bytecode gate: FAILED — {len(missing)} reactor module(s) published no jar, "
          f"so their classes were never scanned:")
    for module in missing:
        print(f"    {module}")
    print("Run a FULL `mvn install` (or `mvn package`) before this gate. A partial build leaves the "
          "gate scanning whatever happens to be on disk and reporting it as a clean result.")
    sys.exit(1)

if scanned == 0:
    print("preview-bytecode gate: FAILED — scanned 0 classes; run `mvn package` first")
    sys.exit(1)

print(f"preview-bytecode gate: scanned {scanned} distributed classes across "
      f"{len(expected_modules)} reactor module(s) "
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
