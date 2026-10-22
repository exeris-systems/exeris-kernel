#!/usr/bin/env bash
#
# Central bundle content gate.
#
# Asserts that the bundle central-publishing-maven-plugin builds holds only files of published
# coordinates: every entry lives in <group path>/<artifact>/<version>/ and is named
# <artifact>-<version>[-classifier].<ext>[.asc|.md5|.sha1|...], and every such directory holds the
# coordinate's .pom.
#
# Why a gate: Central validates the bundle as a whole and refuses it when any directory in it has
# no .pom, so one stray file fails every coordinate. Staging installs each module into a local
# repository, and the bookkeeping that install leaves (maven-metadata-*.xml, _remote.repositories,
# .locks) is named by the resolver of the Maven running the build. The plugin deletes only the names
# it knows; any other reaches the bundle, and the upload is what fails, on an immutable version
# number.
#
# Usage:
#   tools/central-bundle-gate/central-bundle-gate.sh [bundle.zip]   # default: the release bundle
#   tools/central-bundle-gate/central-bundle-gate.sh --self-test    # negative controls
#
# The bundle is written by `mvn -P release clean deploy` (`-DskipUpload=true` builds it without
# uploading it).
set -euo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

if [ "${1:-}" = "--self-test" ]; then
  # The gate must pass a clean bundle and refuse each kind of stray entry; a gate that was never
  # seen to fail proves nothing.
  exec python3 -I - "$SELF" <<'PY'
import subprocess, sys, tempfile, zipfile, pathlib

gate = sys.argv[1]
coord = 'eu/exeris/exeris-kernel-spi/1.0.0/exeris-kernel-spi-1.0.0'
good = [f'{coord}.pom', f'{coord}.jar', f'{coord}.pom.asc', f'{coord}-sources.jar', f'{coord}.jar.sha1']
version_dir = 'eu/exeris/exeris-kernel-spi/1.0.0'
cases = {
    'clean': (good, True),
    'group-level-metadata': (good + ['eu/exeris/maven-metadata-local.xml'], False),
    'artifact-level-metadata': (good + ['eu/exeris/exeris-kernel-spi/maven-metadata-local.xml'], False),
    'remote-repositories': (good + [f'{version_dir}/_remote.repositories'], False),
    'lock-file': (good + [f'{version_dir}/.locks/x.lock'], False),
    'missing-pom': ([n for n in good if not n.endswith('.pom')], False),
}
failed = False
with tempfile.TemporaryDirectory() as tmp:
    for name, (entries, accepted) in cases.items():
        bundle = pathlib.Path(tmp) / f'{name}.zip'
        with zipfile.ZipFile(bundle, 'w') as z:
            for e in entries:
                z.writestr(e, 'x')
        rc = subprocess.run([gate, str(bundle)], capture_output=True).returncode
        ok = (rc == 0) == accepted
        verdict = 'accepted' if rc == 0 else 'refused'
        print(f'self-test: {name}: {verdict}' + ('' if ok else '  <-- WRONG'))
        failed |= not ok
print('central-bundle-gate self-test: ' + ('FAILED' if failed else 'PASSED'))
sys.exit(1 if failed else 0)
PY
fi

BUNDLE="${1:-$REPO_ROOT/target/central-publishing/central-bundle.zip}"
[ -f "$BUNDLE" ] || { echo "central-bundle-gate: FAILED - $BUNDLE is missing; the release profile builds it at \`deploy\`" >&2; exit 1; }

python3 -I - "$BUNDLE" <<'PY'
import re, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as z:
    entries = [n for n in z.namelist() if not n.endswith('/')]

failures = []
poms = set()
directories = set()
for name in entries:
    directory, _, filename = name.rpartition('/')
    parts = directory.split('/')
    # <group path (one or more segments)>/<artifact>/<version>
    if len(parts) < 3 or not re.match(r'\d', parts[-1]):
        failures.append(f'{name}: not inside <group>/<artifact>/<version>/')
        continue
    stem = f'{parts[-2]}-{parts[-1]}'
    if not (filename.startswith(stem + '.') or filename.startswith(stem + '-')):
        failures.append(f'{name}: not a file of coordinate {stem}')
        continue
    directories.add(directory)
    if filename == stem + '.pom':
        poms.add(directory)

for directory in sorted(directories - poms):
    failures.append(f'{directory}: holds no .pom')

if failures:
    print(f'central-bundle-gate: FAILED - {len(failures)} problem(s) in {len(entries)} entries', file=sys.stderr)
    for f in failures:
        print('  ' + f, file=sys.stderr)
    sys.exit(1)
print(f'central-bundle-gate: {len(entries)} file(s) in {len(poms)} coordinate(s), none outside a version directory')
PY
