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
import hashlib
import re
import sys
import xml.etree.ElementTree as ET


def label(name, props, siblings):
    """A key that survives same-named siblings.

    Seven <module name="RegexpSinglelineJava"> declarations sit side by side under TreeWalker, one
    per L0 ban. Keyed on the name alone they overwrite each other and six of the seven — ThreadLocal
    and Unsafe among them — leave the comparison entirely, which is the exact blindness this gate
    exists to refuse. `format` is what actually distinguishes them, so it joins the key; `id` wins
    where a config declares one.
    """
    if siblings.count(name) == 1:
        return name
    for discriminator in ('id', 'format'):
        if discriminator in props:
            return f'{name}[{discriminator}={props[discriminator]}]'
    digest = hashlib.sha256(repr(sorted(props.items())).encode()).hexdigest()[:8]
    return f'{name}#{digest}'


def modules(path):
    """Flatten a Checkstyle config to {qualified module name: {property: value}}."""
    found = {}
    collisions = []

    def walk(node, prefix):
        children = node.findall('module')
        names = [c.get('name') for c in children]
        for child in children:
            props = {p.get('name'): p.get('value') for p in child.findall('property')}
            qualified = f'{prefix}/{label(child.get("name"), props, names)}'
            if qualified in found:
                collisions.append(qualified)
            found[qualified] = props
            walk(child, qualified)

    root = ET.parse(path).getroot()
    found[root.get('name')] = {p.get('name'): p.get('value') for p in root.findall('property')}
    walk(root, root.get('name'))
    if collisions:
        # Two siblings that even `format` cannot tell apart. Comparing them would silently drop one,
        # so the check refuses rather than reporting a parity it did not establish.
        print(f'checkstyle-parity-check: FAIL — {path} has indistinguishable sibling modules, which',
              file=sys.stderr)
        print('  this check cannot compare. Give them distinct `id` properties:', file=sys.stderr)
        for name in sorted(set(collisions)):
            print(f'    {name}', file=sys.stderr)
        sys.exit(1)
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
