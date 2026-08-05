# `spi-api-diff` — SPI compatibility gate

CI tooling, outside the Maven reactor (same status as `tools/jfr-reporter`).
Anchor decision: [ADR-065](../../docs/adr/ADR-065-spi-compatibility-gate.md).

Turns the maturity labels declared in [`docs/stability-matrix.md`](../../docs/stability-matrix.md)
from a statement into a checked property: every build diffs the public SPI against the last released
version and **fails when a surface declared `stable` changes incompatibly**.

## Running it

```bash
# one comparison, gated
tools/spi-api-diff/spi-api-diff.sh --old v0.10.2 --new HEAD --fail-on-stable

# whole release history, report-only
tools/spi-api-diff/spi-api-diff.sh --history v0.5.0,v0.6.0,v0.7.0,v0.7.1,v0.8.0,v0.8.1,v0.9.0,v0.10.0,v0.10.1,v0.10.2

# every SPI class resolves to a maturity label?
tools/spi-api-diff/spi-api-diff.sh --verify-surfaces
```

Reports land in `target/spi-api-diff/` unless `--out` says otherwise.
Exit codes: `0` compatible (or report-only), `1` a `stable` surface broke, `2` usage/tooling error.

Requirements: a JDK 26 on `PATH` and Maven (used once, to fetch japicmp into `~/.m2`). No GitHub
Packages credentials and no published artifacts are needed — see below.

## Why it compiles from git instead of resolving published jars

`exeris-kernel-spi` may depend only on `java.*` / `jdk.*` (The Wall). That constraint has a useful
side effect: **every revision of the SPI module in the project's history compiles standalone with
nothing but a JDK**. So the gate does `git archive <ref> | javac | jar` rather than resolving
`eu.exeris.kernel:exeris-kernel-spi:<version>` from GitHub Packages.

That keeps the gate credential-free (no `PACKAGES_READ_TOKEN` on a fork PR), offline once japicmp is
cached, and able to regenerate the entire history from a clean clone — including releases published
before any of this existed.

## Choosing the baseline

Use the **highest released version**, not `git describe`. Patch releases are cut on `main`, so on a
`development/*` branch `git describe --tags --abbrev=0` returns the last *reachable* tag, which can
be older than the latest published one (on `development/0.11.0` it returns `v0.10.0` while `v0.10.2`
is what consumers actually have). The workflow computes it as:

```bash
git tag --sort=-v:refname --list 'v*' | head -1
```

Reachability does not matter here — the comparison is between two API surfaces, not two histories.

## `stability-surfaces.conf`

The machine-readable mirror of the matrix: which packages and classes are `stable`, `preview`,
`experimental`, or `internal`. **When a surface changes level in the matrix, change it here in the
same commit.** `--verify-surfaces` fails when any SPI **class** resolves to no label, so neither a
new subsystem nor a new class inside an existing `mixed` package can ship unclassified. It caught
`spi.scheduling` and `spi.storage.blob` on its first run, and 25 unclassified `spi.http` classes
once tightened from package to class granularity.

A `mixed` package must therefore be enumerated exhaustively. Package-level entries still match as
prefixes, so a single-maturity package stays one line.

## Failure modes this tool defends against

A compatibility gate that reports "no differences" when it is actually broken is worse than no gate,
because it converts an unknown into a false assurance. Two such bugs were hit while building it, and
both are now asserted against:

- **Wrong include separator.** japicmp separates include expressions with `;`. A comma-separated list
  parses fine, matches nothing, and reports a clean diff. `assert_filter_selects` fails the run if an
  include expression selects zero classes.
- **Silent build failure.** If the SPI at a revision fails to compile, an empty jar compares as
  "everything unchanged". `assert_jar` fails the run unless the artifact contains classes, and
  japicmp output is rejected unless it carries its comparison header.
- **Package-granular self-check.** `--verify-surfaces` originally checked per package, so one
  matching class marked its whole package classified and the rest fell out of both include lists —
  neither gated nor reported. The self-check had the hole it exists to prevent, in the one package
  (`spi.http`) that needs class-level precision. Now checked per fully-qualified class name.

Neither check is decorative; both were live defects that produced green output over known-breaking
revision pairs.
