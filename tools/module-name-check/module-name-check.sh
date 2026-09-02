#!/usr/bin/env bash
#
# Verifies that every published library jar carries a usable Automatic-Module-Name.
#
# Why this is a gate rather than a convention: a jar with no Automatic-Module-Name is still
# usable on the module path — the JDK derives a name from the FILE NAME instead. That derived
# name becomes a de-facto contract from the first release a consumer compiles against, and
# changing it later breaks every `requires` clause that used it. The failure is therefore
# silent at build time and expensive at the far end, which is exactly the shape a gate exists
# for. The same argument covers an unresolved property: `exeris.module.name` left unset in a
# module that declares the jar plugin writes the literal '${exeris.module.name}' into the
# manifest, which is not a legal module name and which nothing else would catch.
#
# Run after a build that produced the jars (mvn install / package).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Every jar-producing module. Only the pom-packaging ones (bom, parent) and the build-time
# ruleset module are outside this, because they publish no jar to name.
#
# Two things about the coverage here were measured rather than assumed. Maven's own jar plugin
# rejects an EMPTY Automatic-Module-Name ("Invalid automatic module name: ''"), so a module that
# declares the plugin without setting the property fails the build before this script runs — which
# narrows the script's job to the case Maven cannot see: a module that never declares the plugin at
# all, and so ships a jar with no entry and no error. And the shade plugin PRESERVES the entry
# through `exeris-kernel-diagnostics-cli`'s shaded artifact, checked on the built jar, so the CLI is
# in the list rather than excluded on a guess about transformers.
MODULES=(
  exeris-kernel-spi
  exeris-kernel-tck
  exeris-kernel-core
  exeris-kernel-community
  exeris-kernel-community-kafka
  exeris-kernel-community-testkit
  exeris-kernel-diagnostics-cli
)

NAME_RE='^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$'

failures=0
checked=0

for module in "${MODULES[@]}"; do
  jar="$(find "$ROOT/$module/target" -maxdepth 1 -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' 2>/dev/null | head -1)"

  if [ -z "$jar" ]; then
    echo "FAIL  $module — no jar in target/; run a build first"
    failures=$((failures + 1))
    continue
  fi

  name="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null \
          | tr -d '\r' | sed -n 's/^Automatic-Module-Name: *//p' | head -1)"

  checked=$((checked + 1))

  if [ -z "$name" ]; then
    echo "FAIL  $module — no Automatic-Module-Name; the JDK would derive one from the file name"
    failures=$((failures + 1))
  elif ! printf '%s' "$name" | grep -Eq "$NAME_RE"; then
    echo "FAIL  $module — '$name' is not a legal module name (unresolved property?)"
    failures=$((failures + 1))
  else
    echo "ok    $module — $name"
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "FAIL  nothing was checked — a gate that inspects no artifact is not a gate"
  exit 1
fi

if [ "$failures" -gt 0 ]; then
  echo
  echo "$failures of ${#MODULES[@]} library jars lack a usable Automatic-Module-Name."
  exit 1
fi

echo
echo "All ${#MODULES[@]} library jars carry a usable Automatic-Module-Name."
