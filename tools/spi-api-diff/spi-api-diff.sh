#!/usr/bin/env bash
#
# spi-api-diff.sh — per-release SPI compatibility diff for exeris-kernel.
#
# Produces a machine-checked compatibility report between two revisions of
# `exeris-kernel-spi`, classified by the maturity labels declared in
# docs/stability-matrix.md, and (optionally) fails when a surface labelled `stable`
# takes an incompatible change.
#
# "Incompatible" is asked in BOTH senses, because the two disagree on the change a
# stability promise most needs to catch. Adding an abstract method to an interface is
# binary-compatible — an existing implementor's class file still links, and fails at
# invoke time with AbstractMethodError — but source-incompatible. A gate that asks only
# the binary question is blind to implementors, who are half of who `stable` speaks to.
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
#   spi-api-diff.sh --self-test
#
# Exit codes: 0 = compatible (or report-only), 1 = stable-surface break, 2 = usage/tooling error.

set -euo pipefail

JAPICMP_VERSION="${JAPICMP_VERSION:-0.23.1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SURFACES_CONF="$SCRIPT_DIR/stability-surfaces.conf"
SPI_PATH="exeris-kernel-spi/src/main/java"
# Derived from the running JDK rather than hardcoded. A fixed value silently rots the moment the
# build's baseline moves, and fails with "release version N not supported" — a message that names
# the flag, not the cause. Deriving also keeps the gate working on whatever JDK a contributor
# happens to run, since a JDK cannot target a release newer than itself. Override with
# SPI_API_DIFF_RELEASE when diffing deliberately against another level.
DETECTED_RELEASE="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java\.specification\.version/ {gsub(/ /,"",$2); print $2}')"
RELEASE_FLAG="${SPI_API_DIFF_RELEASE:-${DETECTED_RELEASE:-25}}"

OLD_REF=""; NEW_REF=""; OUT_DIR=""; FAIL_ON_STABLE=0; HISTORY=""; VERIFY_ONLY=0; SELF_TEST=0

die() { echo "spi-api-diff: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --old) OLD_REF="${2:-}"; shift 2 ;;
    --new) NEW_REF="${2:-}"; shift 2 ;;
    --out) OUT_DIR="${2:-}"; shift 2 ;;
    --history) HISTORY="${2:-}"; shift 2 ;;
    --fail-on-stable) FAIL_ON_STABLE=1; shift ;;
    --verify-surfaces) VERIFY_ONLY=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

OUT_DIR="${OUT_DIR:-$REPO_ROOT/target/spi-api-diff}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- surface configuration -------------------------------------------------

[[ -f "$SURFACES_CONF" ]] || die "missing surface config: $SURFACES_CONF"

read_surface() {  # <level> [<conf-file>]
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
  ' "${2:-$SURFACES_CONF}"
}

load_surfaces() {  # <conf-file>
  STABLE_INC="$(read_surface stable "$1")"
  PREVIEW_INC="$(read_surface preview "$1")"
  EXPERIMENTAL_INC="$(read_surface experimental "$1")"
  INTERNAL_INC="$(read_surface internal "$1")"
  [[ -n "$STABLE_INC" ]] || die "no 'stable' surfaces configured in $1"
}

load_surfaces "$SURFACES_CONF"

# History mode only: the conf a transition is judged by. A release history records what each
# change was held to when it was made, so a transition is labelled by the conf at its OLD ref —
# the surface as it stood while the change was made. Labelling by the current conf would turn
# every past `preview` break into a `stable` break the moment a surface is promoted, and the
# record would then deny a declaration that was kept. The NEW ref's conf is the wrong choice for
# the same reason: a release that promotes a surface carries the promotion in its own conf, so
# the changes that release made while the surface was still `preview` would be read as `stable`.
# A ref that predates the conf file takes the earliest conf among the history's refs, then the
# file's first version in HEAD's history if none has one; the working-tree conf only
# when the file never existed there. The single-run mode never reaches this: it judges by the
# working-tree conf on purpose, to hold every change since a baseline to the current labels.
HISTORY_CONF_PATH="tools/spi-api-diff/stability-surfaces.conf"
HISTORY_FALLBACK_CONF=""

history_conf_for() {  # <ref> -> prints a conf file path
  local ref="$1" dest
  dest="$WORK_DIR/conf-$(echo "$ref" | tr '/' '_')"
  if git -C "$REPO_ROOT" cat-file -e "$ref:$HISTORY_CONF_PATH" 2>/dev/null; then
    git -C "$REPO_ROOT" show "$ref:$HISTORY_CONF_PATH" > "$dest"
    echo "$dest"
  else
    echo "${HISTORY_FALLBACK_CONF:-$SURFACES_CONF}"
  fi
}

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
  javac --release "$RELEASE_FLAG" -nowarn \
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

# Asks japicmp's own source-compatibility verdict instead of counting markers.
#
# `--only-incompatible` reports the BINARY notion, and the two notions disagree on
# exactly the change a `stable` label is a promise against: adding an abstract method
# to an interface is binary-compatible, because an existing implementor's class file
# still links — it fails later, at invoke time, with AbstractMethodError. Callers are
# unaffected; implementors are broken. A gate that only asks the binary question is
# blind to the half of the audience that implements the contract.
#
# So the gate asks japicmp's source-incompatibility check and reads its exit code: the
# binary-incompatibility check exits 0 on a new abstract interface method, and a count of
# binary markers reports the same false green.
japicmp_source_break() {  # <old-jar> <new-jar> <include-list-csv> -> 0 clean, 1 break
  local out rc=0
  out="$(java -jar "$JAPICMP" -o "$1" -n "$2" -a public --ignore-missing-classes \
         -i "$(to_japicmp_list "$3")" --error-on-source-incompatibility 2>&1)" || rc=$?
  # Same guard as japicmp_run: a tooling failure also exits non-zero, and must not be
  # read as "the API broke" any more than as "the API held".
  grep -q '^Comparing' <<< "$out" \
    || die "japicmp produced no comparison header for the source-compatibility check"
  [[ $rc -eq 0 || $rc -eq 1 ]] \
    || die "japicmp failed the source-compatibility check (exit $rc)"
  SOURCE_BREAK_REPORT="$(grep -E '^[[:space:]]+[-+*]{3}\*' <<< "$out" || true)"
  return $rc
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
  [[ -z "$HISTORY" ]] || load_surfaces "$(history_conf_for "$old_ref")"
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

  SOURCE_BREAK_REPORT=""
  local stable_source_breaks=0
  japicmp_source_break "$old_jar" "$new_jar" "$STABLE_INC" || stable_source_breaks=1
  local stable_source_detail="$SOURCE_BREAK_REPORT"

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
    echo "| Source-incompatible changes on \`stable\` surfaces | **$stable_source_breaks** |"
    echo "| Binary-incompatible changes on \`preview\`/\`experimental\` surfaces | $preview_breaks |"
    echo
    echo "Maturity labels are read from \`tools/spi-api-diff/stability-surfaces.conf\`,"
    echo "which mirrors \`docs/stability-matrix.md\`. Only \`public\` API is compared."
    echo
    echo "## \`stable\` surfaces"
    echo
    if [[ "$stable_source_breaks" -ne 0 ]]; then
      echo "> **Gate failure — source compatibility.** A \`stable\` surface changed in a way that"
      echo "> compiles and links for callers but breaks implementors. The usual shape is a new"
      echo "> abstract method on an interface: an existing implementation still links and fails at"
      echo "> invoke time with \`AbstractMethodError\`, which is why the binary check below reports"
      echo "> nothing. Give the member a \`default\`, or demote the surface deliberately."
      echo
      echo '```'
      echo "$stable_source_detail"
      echo '```'
      echo
    fi
    if [[ "$stable_breaks" -eq 0 && "$stable_source_breaks" -eq 0 ]]; then
      echo "No binary- or source-incompatible change. The stability declaration held across this release."
    elif [[ "$stable_breaks" -eq 0 ]]; then
      echo "No *binary*-incompatible change — which is the finding above, not a reassurance against it."
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

  printf '%-10s -> %-10s  semver=%-7s stable-breaks=%-3s stable-src-breaks=%-3s preview-breaks=%-3s  %s\n' \
    "$old_ref" "$new_ref" "$semver" "$stable_breaks" "$stable_source_breaks" "$preview_breaks" "$report"

  if [[ $FAIL_ON_STABLE -eq 1 ]] \
     && { [[ "$stable_breaks" -gt 0 ]] || [[ "$stable_source_breaks" -gt 0 ]]; }; then rc=1; fi
  return $rc
}

# --- self-test ---------------------------------------------------------------

# Which conf labels a transition is the one property of this script a wrong answer would not
# announce: a mislabelled break still prints a well-formed report. So the selection is checked
# against a throwaway repository whose surface `b` is `internal` in the conf file's first version,
# `preview` in the next, and `stable` from the commit that promotes it, with a method of `b` removed
# at every tagged step, the promotion included. Every conf a transition could be judged by gives a
# different answer, so each case has exactly one: a history case reports the label of the conf it
# must pick, and the single-run control reports the working tree's.
self_test() {
  local fx="$WORK_DIR/self-test" failed=0 line
  local -a g=(git -C "$fx" -c user.name=self-test -c user.email=self-test@invalid
              -c commit.gpgsign=false -c tag.gpgsign=false -c core.hooksPath=/dev/null)
  local src="$fx/$SPI_PATH"
  mkdir -p "$fx/tools/spi-api-diff" "$src/a" "$src/b"
  cp "$0" "$fx/tools/spi-api-diff/spi-api-diff.sh"
  "${g[@]}" init -q
  pre_api() {  # <methods...>
    { echo "package b; public class Pre {"
      for m in "$@"; do echo "  public void $m() {}"; done
      echo "}"; } > "$src/b/Pre.java"
  }
  label() {  # <commit-message> <tag>
    "${g[@]}" add -A && "${g[@]}" commit -q -m "$1" && "${g[@]}" tag "$2"
  }
  echo "package a; public class Api { public void keep() {} }" > "$src/a/Api.java"
  pre_api m n k;  label "no conf" t0
  pre_api m n;    label "no conf, k removed" t0b
  printf 'stable=a\ninternal=b\n' > "$fx/$HISTORY_CONF_PATH"
  label "first conf, b internal" tc
  printf 'stable=a\npreview=b\n' > "$fx/$HISTORY_CONF_PATH"
  pre_api n;      label "b preview, m removed" t1
  pre_api z;      label "n removed" t2
  printf 'stable=a,b\npreview=\n' > "$fx/$HISTORY_CONF_PATH"
  pre_api;        label "b promoted, z removed" t3

  expect() {  # <case> <expected stable-breaks> <expected preview-breaks> <script args...>
    local name="$1" want_s="$2" want_p="$3"; shift 3
    line="$(bash "$fx/tools/spi-api-diff/spi-api-diff.sh" "$@" --out "$fx/out" 2>&1 | tail -1 || true)"
    local got_s got_p
    got_s="$(sed -n 's/.*stable-breaks=\([0-9]*\).*/\1/p' <<< "$line")"
    got_p="$(sed -n 's/.*preview-breaks=\([0-9]*\).*/\1/p' <<< "$line")"
    if [[ "$got_s" == "$want_s" && "$got_p" == "$want_p" ]]; then
      echo "self-test: $name: stable-breaks=$got_s preview-breaks=$got_p"
    else
      echo "self-test: $name: expected stable-breaks=$want_s preview-breaks=$want_p, got: $line  <-- WRONG"
      failed=1
    fi
  }
  expect "history labels by the old ref's conf"         0 1 --history t1,t2
  expect "history ignores the new ref's promotion"      0 1 --history t2,t3
  expect "history before the conf takes the earliest"   0 1 --history t0b,t1
  expect "history wholly before the conf takes HEAD's"  0 0 --history t0,t0b
  expect "single run labels by the working tree"        1 0 --old t1 --new t2
  echo "spi-api-diff self-test: $([[ $failed -eq 0 ]] && echo PASSED || echo FAILED)"
  return $failed
}

if [[ $SELF_TEST -eq 1 ]]; then self_test; exit $?; fi

# --- entry points ----------------------------------------------------------

OVERALL=0

if [[ -n "$HISTORY" ]]; then
  IFS=',' read -ra REFS <<< "$HISTORY"
  [[ ${#REFS[@]} -ge 2 ]] || die "--history needs at least two refs"
  for r in "${REFS[@]}"; do
    if git -C "$REPO_ROOT" cat-file -e "$r:$HISTORY_CONF_PATH" 2>/dev/null; then
      HISTORY_FALLBACK_CONF="$(history_conf_for "$r")"; break
    fi
  done
  if [[ -z "$HISTORY_FALLBACK_CONF" ]]; then
    # None of the refs carries the conf: take the first version of the file in HEAD's history,
    # not the working tree, so a range that predates the conf is still judged by the labels the
    # project first declared rather than by today's.
    first_conf_commit="$(git -C "$REPO_ROOT" log --reverse --diff-filter=A --format=%H \
      HEAD -- "$HISTORY_CONF_PATH" | sed -n 1p)"
    [[ -z "$first_conf_commit" ]] || HISTORY_FALLBACK_CONF="$(history_conf_for "$first_conf_commit")"
  fi
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
