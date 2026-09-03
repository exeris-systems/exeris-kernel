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

# The module list is DISCOVERED from the reactor's build output, not transcribed here. That is the
# whole safety property, and it was arrived at by measurement rather than design:
#
# An earlier draft claimed that a jar module which does not set `exeris.module.name` fails the
# build, because maven-jar-plugin rejects an empty automatic module name. That is true only when a
# module DECLARES the plugin explicitly — which is how the claim came to be believed, since two
# modules do. With the configuration inherited from the root pluginManagement and no local
# declaration, the property simply goes unresolved and the manifest entry is OMITTED: the build
# succeeds and ships a nameless jar. Measured, not reasoned about.
#
# So nothing upstream of this script guarantees anything, which makes this script the guarantee —
# and a guarantee behind a hand-maintained list is only as good as somebody remembering to extend
# it. Every jar the reactor produces is checked instead.
#
# Excluded by name: `-sources`, `-javadoc` (not code a consumer compiles against) and `original-*`
# (the shade plugin's pre-shading copy, left beside the jar it replaced — without this the CLI
# matches twice and the check could inspect the copy that is not published).
#
# `-tests` jars are still NOT excluded, and the reason has changed under the rule rather than gone
# away. It used to be that exeris-kernel-tck's classifier-`tests` jar was the ONLY artifact anyone
# consumed — the module had no src/main, so its default jar held seven files of metadata while all
# 492 real classes shipped under the classifier. Excluding test jars would have left the gate
# validating the jar nobody used and skipping the one four modules put on their classpath.
#
# That module now publishes an ordinary jar and no test-jar at all, so no reactor module currently
# produces one. The pattern stays admitted anyway: a module that starts publishing a test jar would
# otherwise acquire an unnamed one silently, which is the class of failure this gate exists to
# refuse. Admitting a shape nothing currently produces costs nothing and closes that door.

NAME_RE='^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$'

failures=0
checked=0

while IFS= read -r jar; do
  # Labelled by jar, not by module: exeris-kernel-tck publishes two (its empty default jar and the
  # classifier-`tests` one everything actually consumes), and a failure message naming only the
  # module would not say which.
  module="$(basename "$jar")"

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
done < <(find "$ROOT" -mindepth 3 -maxdepth 3 -path '*/target/*.jar' \
           ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
           ! -name 'original-*.jar' | sort)

if [ "$checked" -eq 0 ]; then
  echo "FAIL  no jars found under */target/ — run a build first; a gate that inspects no artifact"
  echo "      is not a gate"
  exit 1
fi

if [ "$failures" -gt 0 ]; then
  echo
  echo "$failures of $checked published jars lack a usable Automatic-Module-Name."
  exit 1
fi

echo
echo "All $checked published jars carry a usable Automatic-Module-Name."
