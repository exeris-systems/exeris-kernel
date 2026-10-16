#!/usr/bin/env bash
#
# Removes reachability-metadata.json from an installed reactor jar in ~/.m2.
#
# Used by native-smoke CI and local runs to verify that missing metadata produces
# a red run rather than a false green (ADR-103 obligation 10).
#
# Usage:
#   tools/native-smoke/strip-metadata.sh <artifactId>
#
# The script resolves the active project version via Maven, locates the jar under
# ~/.m2/repository/eu/exeris/<artifactId>/<version>/, verifies the metadata file exists
# in the archive, removes it using zip -d, and verifies it is absent afterwards.
#
# Exit status:
#   0 on successful removal
#   1 if the metadata file was not found (refusing a no-op red run) or removal failed
#   2 on invalid usage
set -euo pipefail

case "${1:-}" in
  -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
esac

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <artifactId>" >&2
  exit 2
fi

ARTIFACT_ID="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

VERSION="$(mvn -f "$ROOT_DIR/pom.xml" help:evaluate -Dexpression=project.version -q -DforceStdout)"
JAR_FILE="$HOME/.m2/repository/eu/exeris/$ARTIFACT_ID/$VERSION/$ARTIFACT_ID-$VERSION.jar"

if [ ! -f "$JAR_FILE" ]; then
  echo "ERROR: Artifact jar not found in ~/.m2: $JAR_FILE" >&2
  exit 1
fi

ENTRY_PATH="META-INF/native-image/eu.exeris/$ARTIFACT_ID/reachability-metadata.json"

if ! unzip -l "$JAR_FILE" | grep -Fq "$ENTRY_PATH"; then
  echo "ERROR: Entry $ENTRY_PATH not found in $JAR_FILE" >&2
  exit 1
fi

echo "Stripping $ENTRY_PATH from $JAR_FILE"
zip -d "$JAR_FILE" "$ENTRY_PATH"

if unzip -l "$JAR_FILE" | grep -Fq "$ENTRY_PATH"; then
  echo "ERROR: Entry $ENTRY_PATH is still present in $JAR_FILE after zip -d" >&2
  exit 1
fi

echo "Successfully removed $ENTRY_PATH from $JAR_FILE"
