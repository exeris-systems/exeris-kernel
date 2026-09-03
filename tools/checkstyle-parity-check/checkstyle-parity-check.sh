#!/usr/bin/env bash
#
# checkstyle-parity-check — keeps checkstyle-tck.xml a DECLARED subtraction of checkstyle.xml.
#
# PMD lets one ruleset reference another and exclude rules from it, so the TCK ruleset is a live
# view of the kernel's: a rule added upstream reaches the TCK unless somebody excludes it on
# purpose. Checkstyle has no such mechanism — a config cannot extend another — so checkstyle-tck.xml
# is a COPY, and the guarantee PMD gets from its format has to be bought here with a check.
#
# Without one, the failure is silent in the direction that matters: a module added to checkstyle.xml
# simply never reaches the ~130 contract-test classes, and nothing says so. The build stays green
# because the TCK config is, by itself, valid.
#
# The check is set equality against a delta the TCK config declares about ITSELF, in lines of the
# form `PARITY-DELTA: drop <Module>` / `PARITY-DELTA: set <Module>.<property>` inside its header
# comment. Both directions fail: an undeclared divergence, and a declaration that no longer
# describes a difference. That keeps the header from becoming prose that outlived the file.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RES="$ROOT/exeris-kernel-build-config/src/main/resources"

python3 - "$RES/checkstyle.xml" "$RES/checkstyle-tck.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET


def modules(path):
    """Flatten a Checkstyle config to {qualified module name: {property: value}}."""
    found = {}

    def walk(node, prefix):
        for child in node.findall('module'):
            name = child.get('name')
            qualified = f'{prefix}/{name}' if prefix else name
            found[qualified] = {
                p.get('name'): p.get('value') for p in child.findall('property')
            }
            walk(child, qualified)

    root = ET.parse(path).getroot()
    found[root.get('name')] = {p.get('name'): p.get('value') for p in root.findall('property')}
    walk(root, root.get('name'))
    return found


def declared(path):
    """The delta the TCK config claims about itself, as {('drop'|'set', target)}."""
    with open(path, encoding='utf-8') as handle:
        text = handle.read()
    return {
        (kind, target)
        for kind, target in re.findall(r'PARITY-DELTA:\s+(drop|set)\s+(\S+)', text)
    }


kernel_path, tck_path = sys.argv[1], sys.argv[2]
kernel, tck = modules(kernel_path), modules(tck_path)

actual = set()
for name, props in kernel.items():
    if name not in tck:
        actual.add(('drop', name.rsplit('/', 1)[-1]))
        continue
    for key, value in props.items():
        if tck[name].get(key) != value:
            actual.add(('set', f'{name.rsplit("/", 1)[-1]}.{key}'))
    for key in tck[name]:
        if key not in props:
            actual.add(('set', f'{name.rsplit("/", 1)[-1]}.{key}'))
for name in tck:
    if name not in kernel:
        actual.add(('add', name.rsplit('/', 1)[-1]))

want = declared(tck_path)
undeclared = sorted(actual - want)
stale = sorted(want - actual)

if undeclared:
    print('checkstyle-parity-check: FAIL — checkstyle-tck.xml diverges from checkstyle.xml without', file=sys.stderr)
    print('  declaring it. Either mirror the change into the TCK config, or add the line shown:', file=sys.stderr)
    for kind, target in undeclared:
        print(f'    PARITY-DELTA: {kind} {target}', file=sys.stderr)
if stale:
    print('checkstyle-parity-check: FAIL — declared delta that is no longer a difference:', file=sys.stderr)
    for kind, target in stale:
        print(f'    PARITY-DELTA: {kind} {target}', file=sys.stderr)
if undeclared or stale:
    sys.exit(1)

print(f'checkstyle-parity-check: {len(kernel)} modules, {len(actual)} declared deltas, no drift.')
PY
