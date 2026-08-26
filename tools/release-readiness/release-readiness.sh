#!/usr/bin/env bash
#
# Release-readiness gate (v0.12 Stream B S3 — Maven Central publication).
#
# Asserts that a `-P release` build produced everything Maven Central accepts, and that every one
# of those files carries a signature that actually verifies.
#
# Why a gate rather than trust in the profile: Central validates PER ARTIFACT, and its documented
# first-release failures are all of the shape "one module is missing one file" — a module where the
# release profile did not activate, a classifier that silently was not attached, a signature that
# was produced against a key the verifier cannot resolve. The reactor build is green in every one
# of those cases; the upload is what fails, hours later, on an immutable channel.
#
# This gate exists because that already happened once here in a quieter form: cyclonedx's
# `skipNotDeployed` defaults to true and central-publishing sets `maven.deploy.skip`, so the
# release build emitted eleven signed artifacts and ZERO SBOMs while the ordinary CI SBOM gate —
# which runs a build where nothing skips deploy — stayed green. A gate that is green about a path
# it does not cover is worse than no gate.
#
# Usage:
#   tools/release-readiness/release-readiness.sh
#
# Requires a FULL `mvn -P release verify` (or `deploy`) first, with a signing key available.
set -euo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
  "") ;;
  *) echo "unknown argument: $1" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

command -v gpg >/dev/null || { echo "release-readiness: FAILED — gpg not on PATH"; exit 1; }

python3 - <<'PY'
import pathlib, subprocess, sys, xml.etree.ElementTree as ET

NS = '{http://maven.apache.org/POM/4.0.0}'


def text(node, tag, default=None):
    v = node.findtext(NS + tag)
    return v.strip() if v is not None else default


def coordinates(pom_path):
    root = ET.parse(pom_path).getroot()
    parent = root.find(NS + 'parent')
    version = text(root, 'version') or (text(parent, 'version') if parent is not None else None)
    return text(root, 'artifactId'), version, text(root, 'packaging', 'jar')


def excluded_from_central():
    """Artifacts the root POM holds back from Central, read from the plugin config itself.

    The exclusion is declared in exactly one place — central-publishing-maven-plugin's
    `<excludeArtifacts>` — and this gate reads it rather than carrying a second copy. A gate with
    its own list drifts from the build the day someone edits one of them, and drifts silently in
    the direction of passing.
    """
    root = ET.parse('pom.xml').getroot()
    return {e.text.strip() for e in root.iter(NS + 'excludeArtifact') if e.text}


def reactor():
    """Every coordinate the reactor publishes, the aggregator included.

    Derived from `<modules>`, not from what is on disk: a gate that enumerates its own scope from
    `*/target/*` answers "what did this build happen to produce", which is precisely the question
    that cannot detect a module whose release profile never activated.
    """
    root = ET.parse('pom.xml').getroot()
    out = [(m.text.strip(), pathlib.Path(m.text.strip()) / 'pom.xml')
           for m in root.iter(NS + 'module')]
    out.append(('.', pathlib.Path('pom.xml')))
    return [(d, p) for d, p in out if p.is_file()]


failures = []
checked = signatures = 0
excluded = excluded_from_central()
skipped = []

for module, pom_path in reactor():
    artifact, version, packaging = coordinates(pom_path)
    if artifact in excluded:
        skipped.append(artifact)
        continue
    target = pathlib.Path(module) / 'target'
    stem = f'{artifact}-{version}'

    # What Central requires for this coordinate. A pom-packaged module ships no jar, so demanding
    # sources and javadoc from it would fail the gate on a correct build.
    required = [target / f'{stem}.pom']
    if packaging != 'pom':
        required += [target / f'{stem}.jar',
                     target / f'{stem}-sources.jar',
                     target / f'{stem}-javadoc.jar']
    # The SBOM is not a Central requirement — it is ours, and it is the file that silently went
    # missing, so it is checked exactly like the ones Central enforces.
    required.append(target / 'bom.json')

    for path in required:
        checked += 1
        if not path.is_file():
            failures.append(f'{artifact}: missing {path} — Central validates per artifact, so one '
                            f'module short of one file fails the whole upload')
            continue

        sig = path.with_suffix(path.suffix + '.asc')
        if not sig.is_file():
            failures.append(f'{artifact}: {path.name} has no detached signature ({sig.name})')
            continue

        signatures += 1
        proc = subprocess.run(['gpg', '--verify', str(sig), str(path)],
                              capture_output=True, text=True)
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout).strip().splitlines()
            failures.append(f'{artifact}: signature on {path.name} does not verify — '
                            f'{detail[-1] if detail else "gpg gave no reason"}')

if checked == 0:
    print('release-readiness: FAILED — checked 0 files; run `mvn -P release verify` first')
    sys.exit(1)

print(f'release-readiness: {checked} required file(s) across the reactor, '
      f'{signatures} signature(s) verified')

# Never silent. An exclusion that stops being mentioned is an exclusion that becomes permanent by
# nobody deciding anything.
for artifact in sorted(skipped):
    print(f'    NOT PUBLISHED to Central: {artifact} '
          f'(excluded in the root POM — see the reason recorded there)')

if failures:
    print(f'\nFAILED — {len(failures)} problem(s):')
    for f in failures:
        print(f'    {f}')
    print('\nA Maven Central release is IMMUTABLE. Fix these before uploading, not after.')
    sys.exit(1)

print('release-readiness: PASSED')
PY
