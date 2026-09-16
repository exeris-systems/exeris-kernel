#!/usr/bin/env bash
#
# SBOM gate (v0.12 Stream B — supply-chain integrity).
#
# Asserts that every artifact this reactor publishes carries a CycloneDX SBOM that describes THAT
# artifact and lists what it actually resolves.
#
# Why a gate at all, when the plugin either runs or does not: the failure mode of an SBOM is not
# absence, it is a file that exists and is wrong. A scope misconfiguration produces an SBOM with an
# empty component list; a stale `target/` produces one describing the previous version; and a plugin
# upgrade that restores the random serial number produces one that is correct but no longer
# reproducible. All three read as success to anything that only checks the file is there.
#
# The reproducibility half of the same stream is checked by `mvn artifact:check-buildplan`, which
# reads the build plan rather than the output and so catches a newly added plugin BEFORE it has
# published anything.
#
# Usage:
#   tools/sbom-gate/sbom-gate.sh
#
# Requires a FULL reactor build first (`mvn package` / `mvn install`): the gate reads generated
# SBOMs, and refuses to run if a module the reactor declares has not produced one.
set -euo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
  "") ;;
  *) echo "unknown argument: $1" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET

NS = '{http://maven.apache.org/POM/4.0.0}'


def text(node, tag, default=None):
    found = node.findtext(NS + tag)
    return found.strip() if found is not None else default


def reactor_modules():
    """Modules the reactor DECLARES, pom-packaged ones included.

    Derived from `<modules>` rather than from a `*/target/bom.json` glob, for the reason the
    preview-bytecode gate learned the hard way: a glob answers "what did this build happen to
    produce", which after `mvn -pl <one-module> package` is a very different question, and the
    gate then reports "everything I found was fine" having looked at one file.

    Pom-packaged modules are in scope deliberately. They publish a coordinate, so they carry an
    SBOM, and the invariant this gate holds is exceptionless: every published coordinate has one.
    Their component list is empty, which is true — a pom carries no code — and the gate asserts
    emptiness for them rather than skipping them, so a pom module that suddenly grows components
    is a finding rather than a shrug.
    """
    root = ET.parse('pom.xml').getroot()
    out = []
    for name in [m.text.strip() for m in root.iter(NS + 'module')]:
        pom = pathlib.Path(name) / 'pom.xml'
        if pom.is_file():
            out.append((name, pom))
    out.append(('.', pathlib.Path('pom.xml')))   # the aggregator publishes its own pom too
    return out


def coordinates(pom_path):
    root = ET.parse(pom_path).getroot()
    parent = root.find(NS + 'parent')
    group = text(root, 'groupId') or (text(parent, 'groupId') if parent is not None else None)
    version = text(root, 'version') or (text(parent, 'version') if parent is not None else None)
    return group, text(root, 'artifactId'), version, text(root, 'packaging', 'jar')


def declared_dependencies(pom_path):
    """Direct, non-test dependencies as the POM declares them, by group:artifact.

    Version is deliberately not compared: it is resolved through the BOM and dependencyManagement,
    so pinning the gate to a literal would make it fail on every dependency bump rather than on a
    wrong SBOM. Group and artifact are what identify the thing a consumer would have to audit.
    """
    root = ET.parse(pom_path).getroot()
    deps = root.find(NS + 'dependencies')
    if deps is None:
        return set()
    out = set()
    for dep in deps.findall(NS + 'dependency'):
        scope = text(dep, 'scope', 'compile')
        if scope == 'test':
            continue
        group, artifact = text(dep, 'groupId'), text(dep, 'artifactId')
        if group and artifact and '${' not in group and '${' not in artifact:
            out.add(f'{group}:{artifact}')
    return out


failures = []
checked = 0
component_total = 0

for module, pom_path in reactor_modules():
    group, artifact, version, packaging = coordinates(pom_path)
    sbom_path = pathlib.Path(module) / 'target' / 'bom.json'

    if not sbom_path.is_file():
        failures.append(f'{artifact}: no SBOM at {sbom_path} — the module publishes a coordinate '
                        f'and must carry one')
        continue

    try:
        sbom = json.loads(sbom_path.read_text())
    except (json.JSONDecodeError, OSError) as e:
        failures.append(f'{artifact}: SBOM is unreadable ({e}) — an unparseable SBOM is an absent one')
        continue

    checked += 1
    components = sbom.get('components', [])
    component_total += len(components)

    if sbom.get('specVersion') != '1.6':
        failures.append(f'{artifact}: CycloneDX specVersion is '
                        f'{sbom.get("specVersion")!r}, expected "1.6"')

    # The two fields that make an SBOM non-reproducible. Both are OFF by configuration today; a
    # plugin upgrade restoring either would leave every other check green while the SBOM became
    # the one output of this build that differs run to run.
    if 'serialNumber' in sbom:
        failures.append(f'{artifact}: SBOM carries a serialNumber — it is a fresh UUID per run '
                        f'and makes the SBOM non-reproducible')
    if sbom.get('metadata', {}).get('timestamp') is not None:
        failures.append(f'{artifact}: SBOM metadata carries a timestamp — same reproducibility '
                        f'break as the serial number, by a different field')

    described = sbom.get('metadata', {}).get('component', {})
    expected_purl_prefix = f'pkg:maven/{group}/{artifact}@{version}'
    if not described.get('purl', '').startswith(expected_purl_prefix):
        failures.append(f'{artifact}: SBOM describes {described.get("purl")!r}, not '
                        f'{expected_purl_prefix!r} — a stale target/ produces exactly this')

    listed = {f'{c.get("group")}:{c.get("name")}' for c in components}
    declared = declared_dependencies(pom_path)

    if packaging == 'pom':
        if components:
            failures.append(f'{artifact}: pom-packaged module lists {len(components)} component(s); '
                            f'it carries no code, so this needs explaining rather than accepting')
    else:
        missing = sorted(declared - listed)
        if missing:
            failures.append(f'{artifact}: SBOM omits {len(missing)} directly declared '
                            f'non-test dependency/ies: {", ".join(missing)}')

if checked == 0:
    print('sbom gate: FAILED — checked 0 SBOMs; run `mvn package` before this gate')
    sys.exit(1)

print(f'sbom gate: {checked} SBOM(s) checked across the reactor, '
      f'{component_total} component(s) listed in total')

if failures:
    print(f'\nFAILED — {len(failures)} problem(s):')
    for f in failures:
        print(f'    {f}')
    sys.exit(1)

print('sbom gate: PASSED')
PY
