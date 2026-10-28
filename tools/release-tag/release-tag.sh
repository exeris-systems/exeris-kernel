#!/usr/bin/env bash
#
# Release tag and version validation for the release workflow.
#
# A release is exactly vMAJOR.MINOR.PATCH, and a release candidate is exactly
# vMAJOR.MINOR.PATCH-RC<n> with an upper-case RC and a decimal n. Nothing else is accepted: a
# lower-case `-rc1`, a bare `-RC`, a `-SNAPSHOT`, a preview-line suffix and a tag without its `v`
# are all refused, as are leading zeros (`v01.0.0`) and candidate number 0 or 01: the candidate
# number starts at 1. The Maven version of a tag is the tag without its leading `v`, so the tag, the
# root pom's <version> and the Central coordinate are one string.
#
# Why one script: the workflow's tag trigger is a permissive glob, so this validation is the only
# thing that keeps a suffixed tag from becoming a release. Keeping the pattern in one tested place
# is what stops the tag check and the dispatch check drifting apart.
#
# Usage:
#   tools/release-tag/release-tag.sh --tag vX.Y.Z[-RCn]     # print the Maven version, or exit 1
#   tools/release-tag/release-tag.sh --version X.Y.Z[-RCn]  # validate a bare version, print it
#   tools/release-tag/release-tag.sh --self-test            # positive and negative controls
set -euo pipefail

case "${1:-}" in
  -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
esac

VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-RC[1-9][0-9]*)?$'

validate_version() {
  [[ "$1" =~ $VERSION_RE ]]
}

# Prints the Maven version for a tag; fails for anything that is not a release or a candidate.
version_of_tag() {
  local tag="$1"
  [[ "$tag" == v* ]] || return 1
  validate_version "${tag#v}" || return 1
  printf '%s\n' "${tag#v}"
}

self_test() {
  local failed=0 tag
  for tag in v0.13.0 v1.0.0 v1.0.0-RC1 v1.0.0-RC12; do
    if [ "$(version_of_tag "$tag" || true)" != "${tag#v}" ]; then
      echo "  FAIL: $tag should be accepted as ${tag#v}" >&2; failed=1
    fi
  done
  for tag in v1.0.0-rc1 v1.0.0-RC v1.0.0-RC1a v1.0.0-RC0 v1.0.0-RC01 v01.0.0 v1.00.0 v1.0.0-SNAPSHOT v1.0.0-preview \
             v1.0 v1 1.0.0 1.0.0-RC1 V1.0.0 v1.0.0-RC1-RC2 v1.0.0- '' v preview/v1.0.0; do
    if version_of_tag "$tag" >/dev/null; then
      echo "  FAIL: '$tag' should be refused" >&2; failed=1
    fi
  done
  # The bare-version form (the dispatch input) accepts the same two shapes and no tag form.
  for tag in 0.13.0 1.0.0-RC1; do
    validate_version "$tag" || { echo "  FAIL: version $tag should be accepted" >&2; failed=1; }
  done
  for tag in 1.0.0-SNAPSHOT v1.0.0 1.0.0-rc1 1.0; do
    ! validate_version "$tag" || { echo "  FAIL: version $tag should be refused" >&2; failed=1; }
  done
  # Orderings the candidate flow relies on: the base-branch resolver returns the 1.0 line after
  # 0.13.0, and the SPI baseline filter never selects a candidate tag.
  [ "$(printf 'development/0.13.0\ndevelopment/1.0.0\ndevelopment/0.9.0\n' | sort -V | tail -1)" = development/1.0.0 ] \
    || { echo "  FAIL: sort -V does not place development/1.0.0 last" >&2; failed=1; }
  [ "$(printf 'v0.13.0\nv1.0.0-RC1\nv0.9.0\n' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)" = v0.13.0 ] \
    || { echo "  FAIL: the baseline filter lets a candidate through" >&2; failed=1; }
  if [ "$failed" -ne 0 ]; then
    echo "release-tag: self-test FAILED" >&2; return 1
  fi
  echo "release-tag: self-test passed (4 tags accepted, 19 refused, version form, resolver and baseline ordering)"
}

case "${1:-}" in
  --tag)
    version_of_tag "${2:-}" || { echo "release-tag: '${2:-}' is not a release tag (expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-RC<n>)" >&2; exit 1; } ;;
  --version)
    validate_version "${2:-}" || { echo "release-tag: '${2:-}' is not a release version (expected MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-RC<n>)" >&2; exit 1; }
    printf '%s\n' "$2" ;;
  --self-test) self_test ;;
  *) echo "usage: release-tag.sh --tag TAG | --version VERSION | --self-test" >&2; exit 2 ;;
esac
