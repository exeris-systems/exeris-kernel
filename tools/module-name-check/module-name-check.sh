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
# whole safety property.
#
# A jar module that does not set `exeris.module.name` does not fail the build. maven-jar-plugin
# rejects an empty automatic module name only when a module declares the plugin itself; with the
# configuration inherited from the root pluginManagement and no local declaration, the property
# goes unresolved, the manifest entry is omitted, and the build ships a nameless jar.
#
# So nothing upstream of this script guarantees a name, which makes this script the guarantee — and
# a guarantee behind a hand-maintained list holds only while somebody remembers to extend it. Every
# jar the reactor produces is checked instead.
#
# Excluded by name: `-sources`, `-javadoc` (not code a consumer compiles against) and `original-*`
# (the shade plugin's pre-shading copy, left beside the jar it replaced — without this the CLI
# matches twice and the check could inspect the copy that is not published).
#
# `-tests` jars are NOT excluded. A test jar is an artifact a consumer can put on its classpath, and
# a module that starts publishing one would otherwise ship it unnamed without any check noticing,
# which is the class of failure this gate exists to refuse. No reactor module produces one today;
# admitting the shape costs nothing.

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
