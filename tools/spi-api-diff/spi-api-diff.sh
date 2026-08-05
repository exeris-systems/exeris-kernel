#!/usr/bin/env bash
#
# spi-api-diff.sh — per-release SPI compatibility diff for exeris-kernel.
#
# Produces a machine-checked binary-compatibility report between two revisions of
# `exeris-kernel-spi`, classified by the maturity labels declared in
# docs/stability-matrix.md, and (optionally) fails when a surface labelled `stable`
# takes a binary-incompatible change.
#
# Why it compiles from git rather than resolving published artifacts: the SPI module
# depends only on `java.*` / `jdk.*` (The Wall), so every revision in history compiles
# standalone with nothing but a JDK. That keeps the gate free of GitHub Packages
# credentials, works offline once japicmp is cached, and lets the whole release
# history be regenerated from a clean clone.
#
# Usage:
#   spi-api-diff.sh --old <git-ref> --new <git-ref> [--out <dir>] [--fail-on-stable]
#   spi-api-diff.sh --history <ref>[,<ref>...] [--out <dir>]
#   spi-api-diff.sh --verify-surfaces [--new <git-ref>]
#
# Exit codes: 0 = compatible (or report-only), 1 = stable-surface break, 2 = usage/tooling error.

set -euo pipefail

JAPICMP_VERSION="${JAPICMP_VERSION:-0.23.1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SURFACES_CONF="$SCRIPT_DIR/stability-surfaces.conf"
SPI_PATH="exeris-kernel-spi/src/main/java"
RELEASE_FLAG="${SPI_API_DIFF_RELEASE:-26}"

OLD_REF=""; NEW_REF=""; OUT_DIR=""; FAIL_ON_STABLE=0; HISTORY=""; VERIFY_ONLY=0

die() { echo "spi-api-diff: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --old) OLD_REF="${2:-}"; shift 2 ;;
    --new) NEW_REF="${2:-}"; shift 2 ;;
    --out) OUT_DIR="${2:-}"; shift 2 ;;
    --history) HISTORY="${2:-}"; shift 2 ;;
    --fail-on-stable) FAIL_ON_STABLE=1; shift ;;
    --verify-surfaces) VERIFY_ONLY=1; shift ;;
    -h|--help) sed -n '3,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

OUT_DIR="${OUT_DIR:-$REPO_ROOT/target/spi-api-diff}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- surface configuration -------------------------------------------------

[[ -f "$SURFACES_CONF" ]] || die "missing surface config: $SURFACES_CONF"

read_surface() {
  # Joins the continued lines of a `<level>=` entry into one comma-separated string.
  awk -v key="$1" '
    $0 ~ "^"key"=" { collecting = 1; sub("^"key"=", ""); }
    collecting {
      line = $0
      cont = (line ~ /\\$/)
      gsub(/\\$/, "", line)
      gsub(/[[:space:]]/, "", line)
      out = out line
      if (!cont) exit
      next
    }
    END { print out }
  ' "$SURFACES_CONF"
}

STABLE_INC="$(read_surface stable)"
PREVIEW_INC="$(read_surface preview)"
EXPERIMENTAL_INC="$(read_surface experimental)"
INTERNAL_INC="$(read_surface internal)"

[[ -n "$STABLE_INC" ]] || die "no 'stable' surfaces configured in $SURFACES_CONF"

# --- japicmp resolution ----------------------------------------------------

resolve_japicmp() {
  local jar
  jar="$(find "${HOME}/.m2/repository/com/github/siom79/japicmp" \
        -name "japicmp-${JAPICMP_VERSION}-jar-with-dependencies.jar" 2>/dev/null | head -1)"
  if [[ -z "$jar" ]]; then
    echo "spi-api-diff: fetching japicmp ${JAPICMP_VERSION}..." >&2
    mvn -q dependency:get \
      -Dartifact="com.github.siom79.japicmp:japicmp:${JAPICMP_VERSION}:jar:jar-with-dependencies" \
      >&2 || die "could not fetch japicmp ${JAPICMP_VERSION}"
    jar="$(find "${HOME}/.m2/repository/com/github/siom79/japicmp" \
          -name "japicmp-${JAPICMP_VERSION}-jar-with-dependencies.jar" 2>/dev/null | head -1)"
  fi
  [[ -n "$jar" ]] || die "japicmp jar not found after fetch"
  echo "$jar"
}

# --- build one revision's SPI into a jar -----------------------------------

build_ref() {
  local ref="$1"
  local dest
  dest="$WORK_DIR/$(echo "$ref" | tr '/' '_')"
  mkdir -p "$dest/src" "$dest/out"
  git -C "$REPO_ROOT" rev-parse --verify --quiet "$ref^{commit}" >/dev/null \
    || die "not a git revision: $ref"
  git -C "$REPO_ROOT" archive "$ref" "$SPI_PATH" 2>/dev/null | tar -x -C "$dest/src" \
    || die "revision $ref has no $SPI_PATH"
  find "$dest/src" -name '*.java' > "$dest/files.txt"
  [[ -s "$dest/files.txt" ]] || die "no SPI sources at $ref"
  javac --enable-preview --release "$RELEASE_FLAG" -nowarn \
        -d "$dest/out" "@$dest/files.txt" 2>"$dest/javac.log" \
    || { sed 's/^/    /' "$dest/javac.log" >&2; die "SPI at $ref does not compile"; }
  ( cd "$dest/out" && jar cf "$dest/spi.jar" . )
  echo "$dest/spi.jar"
}

# --- surface classification guard ------------------------------------------

verify_surfaces() {
  local ref="${1:-HEAD}" missing=0 checked=0
  local classified="${STABLE_INC},${PREVIEW_INC},${EXPERIMENTAL_INC},${INTERNAL_INC}"
  # Checked per fully-qualified CLASS, not per package. A package-granular check
  # cannot see a gap inside a `mixed` package: `spi.http` is classified by class
  # name, so the moment one class there matched, the whole directory counted as
  # covered and its remaining classes fell out of BOTH japicmp include lists —
  # ungated and unreported, which is the false-green this gate exists to prevent.
  # Package-level entries in the conf still work here: they match as prefixes.
  while read -r fqcn; do
    checked=$((checked + 1))
    local hit=0
    IFS=',' read -ra entries <<< "$classified"
    for e in "${entries[@]}"; do
      [[ -z "$e" ]] && continue
      # Classified when the entry IS the class, or is a package containing it.
      # Note there is deliberately no "entry is below the class" branch — that
      # is what let one class-level entry classify its whole package.
      if [[ "$fqcn" == "$e" || "$fqcn" == "$e".* ]]; then hit=1; break; fi
    done
    if [[ $hit -eq 0 ]]; then
      echo "spi-api-diff: UNCLASSIFIED SPI class: $fqcn" >&2
      missing=1
    fi
  done < <(git -C "$REPO_ROOT" ls-tree -r "$ref" --name-only -- "$SPI_PATH" \
           | grep '\.java$' \
           | grep -v '/package-info\.java$' \
           | sed "s#^$SPI_PATH/##; s#\.java\$##; s#/#.#g" \
           | LC_ALL=C sort -u)
  [[ $checked -gt 0 ]] || die "no SPI sources found at $ref — refusing to report a vacuous pass"
  if [[ $missing -eq 1 ]]; then
    echo "spi-api-diff: every SPI class must resolve to a maturity label in stability-surfaces.conf" >&2
    echo "spi-api-diff: (and a matching row in docs/stability-matrix.md)" >&2
    return 1
  fi
  echo "spi-api-diff: all $checked SPI classes at $ref are classified."
  return 0
}

if [[ $VERIFY_ONLY -eq 1 ]]; then
  verify_surfaces "${NEW_REF:-HEAD}"
  exit $?
fi

JAPICMP="$(resolve_japicmp)"
mkdir -p "$OUT_DIR"

# --- one comparison --------------------------------------------------------

# japicmp separates include expressions with ';' — a ',' separated list silently
# matches nothing and reports a clean diff. `assert_filter_selects` below exists
# because that failure mode is indistinguishable from success in the output.
to_japicmp_list() { echo "${1//,/;}"; }

japicmp_run() {  # <old-jar> <new-jar> <include-list-csv> <extra-args...>
  local old="$1" new="$2" inc; inc="$(to_japicmp_list "$3")"; shift 3
  local out
  # stderr is kept: a japicmp failure must never look like "no differences found".
  out="$(java -jar "$JAPICMP" -o "$old" -n "$new" \
         -a public --ignore-missing-classes -i "$inc" "$@")" \
    || die "japicmp failed comparing $old -> $new"
  grep -q '^Comparing' <<< "$out" \
    || die "japicmp produced no comparison header for $old -> $new (refusing to report a silent pass)"
  echo "$out"
}

# Proves the include expression actually selects classes. A typo, a renamed
# package, or a wrong separator would otherwise yield "no incompatible changes"
# — a false green, the one outcome this gate must never produce.
assert_filter_selects() {  # <old-jar> <new-jar> <include-list-csv> <label>
  local old="$1" new="$2" inc="$3" label="$4" out n
  [[ -z "$inc" ]] && return 0
  out="$(java -jar "$JAPICMP" -o "$old" -n "$new" -a public --ignore-missing-classes \
         -i "$(to_japicmp_list "$inc")")" || die "japicmp failed while probing the $label filter"
  n="$(grep -cE '^(\*\*\*|---|\+\+\+|===)' <<< "$out" || true)"
  [[ "${n:-0}" -gt 0 ]] \
    || die "the '$label' include filter selected no classes — check stability-surfaces.conf against the SPI tree"
}

# A jar that failed to build must abort the run. Without this an empty artifact
# compares as "no differences", which is the one failure mode a compatibility
# gate must never have.
assert_jar() {  # <path> <ref>
  local jar="$1" ref="$2"
  [[ -s "$jar" ]] || die "empty or missing SPI jar for $ref"
  local n
  n="$(unzip -l "$jar" 2>/dev/null | grep -c '\.class$' || true)"
  [[ "${n:-0}" -gt 0 ]] || die "SPI jar for $ref contains no classes"
}

compare_refs() {  # <old-ref> <new-ref>
  local old_ref="$1" new_ref="$2"
  local old_jar new_jar semver stable_out preview_out rc=0
  old_jar="$(build_ref "$old_ref")" || die "could not build SPI at $old_ref"
  new_jar="$(build_ref "$new_ref")" || die "could not build SPI at $new_ref"
  assert_jar "$old_jar" "$old_ref"
  assert_jar "$new_jar" "$new_ref"

  semver="$(java -jar "$JAPICMP" -o "$old_jar" -n "$new_jar" \
            -a public --ignore-missing-classes --semantic-versioning | tail -1)" \
    || die "japicmp semantic-versioning failed for $old_ref -> $new_ref"

  assert_filter_selects "$old_jar" "$new_jar" "$STABLE_INC" "stable"

  # Counted at column 0: each match is one affected top-level class, not each
  # removed member, so the number reads as "how many contracts moved".
  stable_out="$(japicmp_run "$old_jar" "$new_jar" "$STABLE_INC" --only-incompatible)"
  local stable_breaks
  stable_breaks="$(grep -cE '^(---!|\*\*\*!)' <<< "$stable_out" || true)"

  local non_stable="${PREVIEW_INC}${EXPERIMENTAL_INC:+,$EXPERIMENTAL_INC}"
  local preview_breaks=0
  preview_out=""
  if [[ -n "$non_stable" ]]; then
    assert_filter_selects "$old_jar" "$new_jar" "$non_stable" "preview/experimental"
    preview_out="$(japicmp_run "$old_jar" "$new_jar" "$non_stable" --only-incompatible)"
    preview_breaks="$(grep -cE '^(---!|\*\*\*!)' <<< "$preview_out" || true)"
  fi

  local slug="${old_ref//\//_}-to-${new_ref//\//_}"
  local report="$OUT_DIR/spi-api-diff-${slug}.md"
  {
    echo "# SPI API diff — \`$old_ref\` → \`$new_ref\`"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| Semver suggestion (japicmp) | \`$semver\` |"
    echo "| Binary-incompatible changes on \`stable\` surfaces | **$stable_breaks** |"
    echo "| Binary-incompatible changes on \`preview\`/\`experimental\` surfaces | $preview_breaks |"
    echo
    echo "Maturity labels are read from \`tools/spi-api-diff/stability-surfaces.conf\`,"
    echo "which mirrors \`docs/stability-matrix.md\`. Only \`public\` API is compared."
    echo
    echo "## \`stable\` surfaces"
    echo
    if [[ "$stable_breaks" -eq 0 ]]; then
      echo "No binary-incompatible change. The stability declaration held across this release."
    else
      echo "> **Gate failure.** A surface declared \`stable\` changed incompatibly. Either restore"
      echo "> compatibility, or make the change deliberate: demote the surface in"
      echo "> \`docs/stability-matrix.md\` (and this config) and record the break in the release notes."
      echo
      echo '```'
      echo "$stable_out" | grep -vE '^(Comparing|WARNING)' | sed '/^[[:space:]]*$/d'
      echo '```'
    fi
    echo
    echo "## \`preview\` / \`experimental\` surfaces"
    echo
    if [[ "$preview_breaks" -eq 0 ]]; then
      echo "No binary-incompatible change."
    else
      echo "Reported, not gated — \`preview\` permits breaking changes before promotion"
      echo "(see \`docs/stability-matrix.md\` §Semver policy). Call these out in the release notes."
      echo
      echo '```'
      echo "$preview_out" | grep -vE '^(Comparing|WARNING)' | sed '/^[[:space:]]*$/d'
      echo '```'
    fi
  } > "$report"

  printf '%-10s -> %-10s  semver=%-7s stable-breaks=%-3s preview-breaks=%-3s  %s\n' \
    "$old_ref" "$new_ref" "$semver" "$stable_breaks" "$preview_breaks" "$report"

  if [[ "$stable_breaks" -gt 0 && $FAIL_ON_STABLE -eq 1 ]]; then rc=1; fi
  return $rc
}

# --- entry points ----------------------------------------------------------

OVERALL=0

if [[ -n "$HISTORY" ]]; then
  IFS=',' read -ra REFS <<< "$HISTORY"
  [[ ${#REFS[@]} -ge 2 ]] || die "--history needs at least two refs"
  prev=""
  for r in "${REFS[@]}"; do
    if [[ -n "$prev" ]]; then compare_refs "$prev" "$r" || OVERALL=1; fi
    prev="$r"
  done
else
  [[ -n "$OLD_REF" && -n "$NEW_REF" ]] || die "need --old and --new (or --history)"
  compare_refs "$OLD_REF" "$NEW_REF" || OVERALL=1
fi

exit $OVERALL
