---
title: "ADR-065: The SPI stability declaration is machine-enforced"
type: adr
visibility: public
owning-repo: exeris-kernel
status: active
slug: adr/ADR-065
---

# ADR-065: The SPI stability declaration is machine-enforced

| Attribute       | Value                                                                                    |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                             |
| **Deciders**    | Arkadiusz Przychocki                                                                     |
| **Date**        | 2026-08-05                                                                               |
| **Amended**     | 2026-10-07 — **ACCEPTED** (2026-10-08): A1, release candidates and the SPI freeze before 1.0.0 — see [Amendments](#amendments) |
| **Scope**       | `kernel/build`                                                                           |
| **Owning Repo** | `exeris-kernel`                                                                          |
| **Driven By**   | [`docs/stability-matrix.md`](../stability-matrix.md) — a maturity declaration with nothing checking it; the 1.0-readiness audit's "japicmp absent" table-stakes gap |
| **Compliance**  | [The Wall](../architecture.md) (SPI depends only on `java.*` / `jdk.*` — the property this gate is built on); [Glass-Box](../whitepaper.md) (observable evidence over assertion) |

## Context and Problem Statement

`docs/stability-matrix.md` declares which SPI surfaces are settled. It is a careful document, and
until now it was also an unchecked one: nothing in the build compared one release's SPI to the next,
so a contract could move on a surface labelled `stable` and nobody would learn about it until a
consumer failed to compile.

That is not hypothetical. Regenerating the record after the fact shows three binary-incompatible
transitions between 0.5.0 and 0.10.2 — none of them announced as such at the time, and one of them
(`TelemetryConfig.blackBoxOffHeapBytes()` → `glassBoxOffHeapBytes()`, v0.9.0) invisible to any
review that reads diffs rather than bytecode, because the file survived and only a record component
moved.

Two things sharpen this from housekeeping into a decision.

**The instrument matters.** A source-level or file-inventory diff is not sufficient evidence about
API compatibility. It cannot see a removed record component, a removed constructor, or an interface
method dropped where the file still exists. Applied to 0.5.0 → 0.10.2 it reports *one* incompatible
transition; bytecode comparison finds *three*. Any process that relies on reading diffs to catch
contract movement is relying on an instrument that does not measure the thing.

**The declaration has a date.** The matrix was first published in v0.9.0. Transitions before that
were not violations of anything — there was no promise in force. From v0.9.0 the promise exists, and
the question "has it been kept?" becomes answerable and worth answering. As of this ADR the answer
is yes: the one breaking transition since publication lands entirely on `spi.events`, which the
matrix labels `preview`, where the policy permits it. That claim is only worth making if something
other than a person's memory can check it.

The project's own framing is that a Glass-Box beats a log. The same standard applied to stability
says: a declaration that is never measured is a claim, not a property.

## 🏁 The Decision

A build gate, `tools/spi-api-diff/`, sits outside the Maven reactor (the placement `tools/jfr-reporter`
already established for CI tooling) and runs as its own CI job. It compares the public SPI at two
revisions with [japicmp](https://siom79.github.io/japicmp/) and **fails the build on a
binary-incompatible change to a surface the stability matrix declares `stable`**.

*(Amendment A1, accepted 2026-10-08: which tag is "the last release" once release candidates exist,
and what the gate freezes between a candidate and 1.0.0 — see [Amendments](#amendments).)*

Four rulings carry the decision.

### 1. The gate compiles the SPI from git, not from published artifacts

The obvious implementation resolves `eu.exeris.kernel:exeris-kernel-spi:<previous>` from GitHub
Packages. This one does `git archive <ref> | javac | jar` instead.

That is available only because of The Wall. `exeris-kernel-spi` may depend on nothing but `java.*`
and `jdk.*`, which has a side effect nobody designed for: **every revision of the SPI module in the
project's history compiles standalone with a bare JDK**. Verified across all ten release tags.

The consequences are worth the unusual choice. The gate needs no `PACKAGES_READ_TOKEN`, so it runs on
fork PRs where secrets are absent; it works offline once japicmp is cached; and it can regenerate the
complete release record from a clean clone, including releases published long before it existed —
which is how [`../release/spi-api-history.md`](../release/spi-api-history.md) covers 0.5.0 onward
rather than starting from today.

### 2. Severity follows the declared maturity label

The gate does not apply one rule to all of SPI. `stable` fails the build; `preview` and
`experimental` are reported and do not. That is the semver policy in
[`../stability-matrix.md`](../stability-matrix.md) §"Semver policy" executed rather than restated.

The structural consequence is the point: **the matrix becomes the gate's configuration**, not prose
sitting beside it. `tools/spi-api-diff/stability-surfaces.conf` mirrors the table and must move in
the same commit as any maturity change. Where the generated record and the table disagree, the record
wins and the table is the bug.

### 3. An unclassified SPI class fails the build

`--verify-surfaces` fails when a **class** in the SPI tree resolves to no maturity label.
Classification is mandatory, not opt-in — an unlabelled surface is one the gate cannot protect and a
consumer cannot reason about.

The unit is the class, not the package, and that distinction is the whole guard. A `mixed` package
is classified by class name, so a package-granular check is satisfied by its *first* matching class
and blind to every other one. Since japicmp compares only what an include expression selects, a class
named in no list is neither gated nor reported. `spi.http` is the package this matters for: 38
classes, of which the matrix originally enumerated 12.

This was not a theoretical guard, in either form. The package-granular first version found
`spi.scheduling` and `spi.storage.blob` shipping on the 0.11 line with accepted ADRs (057 / 056),
`Abstract*Tck` coverage and Community bindings, and no row in the matrix. Tightening it to class
granularity then found 25 unclassified classes in `spi.http` — including `HttpRequest`,
`HttpResponse` and `HttpStatus`, the carriers the `stable` engine and handler contracts are written
in terms of. The matrix's `…spi.http` breakdown is now exhaustive by construction, and two surfaces
it had never described at all (client retry, ADR-045; route authorization, ADR-061) have rows.

### 4. A failure is a decision prompt, not an automatic revert

Three responses are legitimate, and the gate's output says so:

1. **Unintended** — restore compatibility. For a record that gained a component, retaining the
   previous canonical constructor as an explicit overload is usually enough.
2. **Intended, surface mislabelled** — demote it in the matrix *and* the config, in one commit, and
   say why in the release notes.
3. **Intended, and the surface really is stable** — that is a major-version question, and pre-1.0 it
   needs an ADR, not a build-config edit.

The matrix is allowed to be wrong. It is not allowed to be quietly wrong.

## Non-revisions

- **The pre-1.0 caveat stands.** Minor versions may still carry observable contract additions; this
  gate does not convert `stable` into a semver-binding promise before 1.0. It makes the *intent*
  checkable, which is a different and smaller claim.
  *(Amendment A1, accepted 2026-10-08: the scope of the freeze from the first 1.0.0 release
  candidate onward — see [Amendments](#amendments).)*
- **`preview` is not weakened into a free-for-all.** Changes there are reported in every release diff
  and belong in the release notes; they are ungated, not unrecorded.
- **Community and Core are out of scope.** `eu.exeris.kernel.community.*` is a driver tier, not a
  consumer contract. Gating it would freeze implementation detail.

## Consequences

### ✅ Positive Outcomes

- A consumer can check the compatibility claim instead of believing it: one generated row per release
  transition, reproducible from a clean clone.
- Contract movement is caught **before** a release rather than after one. The first run against the
  0.11 line flagged `FlowSnapshot` — see the trade-off below.
- The matrix acquires a maintenance forcing-function. A surface can no longer drift out of its label
  quietly, and a new subsystem cannot ship unclassified.
- Closes the "japicmp absent" entry in the 1.0-readiness table-stakes list.

### ⚠️ Trade-offs

- **The gate fails today, and that is the intended behaviour.** `FlowSnapshot` gaining a component
  under [ADR-062](./ADR-062-flow-step-identity-on-resume.md) changes its canonical constructor — a
  binary-incompatible change to `spi.flow`, declared `stable`. Taking that break pre-1.0 is
  defensible; taking it *silently* is what this ADR ends. The choice between retaining the old
  canonical constructor as an overload and recording the change deliberately is owed at the v0.11
  cut.
- **Two labels of truth to keep in sync.** The matrix and `stability-surfaces.conf` say the same
  thing in two places. `--verify-surfaces` catches an *omission*; it cannot catch a *disagreement*
  where both files name a package but at different levels. That check is worth adding later; it is
  not built now.
- **A gate reporting a false green would be worse than no gate**, because it converts an unknown into
  an assurance. **Three** such defects appeared, and the pattern is worth naming: every one of them
  failed *open* and looked like success.
  1. japicmp separates include expressions with `;`, so a comma-separated list matches nothing and
     reports a clean diff → `assert_filter_selects` fails the run if an expression selects no class.
  2. An SPI revision that fails to compile yields an empty jar that compares as unchanged →
     `assert_jar` requires the artifact to contain classes, and japicmp output is rejected without
     its comparison header.
  3. `--verify-surfaces` checked per package, so one matching class marked its whole package
     classified and the rest fell out of both include lists — **the self-check had the exact hole it
     exists to prevent**, in the one package (`spi.http`) singled out for class-level treatment.
     Found in review, not by the tool. Fixed by checking per fully-qualified class name; package
     entries in the config still match as prefixes, so only `mixed` packages pay the cost of being
     enumerated.

  Defect 3 is the instructive one: the first two were caught because they made the tool visibly
  wrong on a known-breaking pair, while the third made it *quietly* right on the pairs anyone would
  test. Regenerating the whole history after the fix returned identical counts — the hole existed,
  but nothing had gone through it — which is the outcome to expect and not evidence the check was
  unnecessary.
- **japicmp is a new build-time dependency**, fetched once into `~/.m2`. It is not a runtime
  dependency and does not enter the reactor.

### 📋 What is NOT in scope

- Source-compatibility gating (the gate reasons about binary compatibility) and behavioural
  compatibility, which no bytecode tool can see.
- Enforcing the matrix against Enterprise surfaces — a separate distribution, out of this repo.
- A deprecation-window mechanism. Declaring one is a 1.0 question; this ADR only makes the current
  declaration checkable.

## Cross-references

- [`../stability-matrix.md`](../stability-matrix.md) — the declaration this gate enforces.
- [`../release/spi-api-history.md`](../release/spi-api-history.md) — the generated per-transition record.
- [`../release/upgrade-0.5-to-0.10.md`](../release/upgrade-0.5-to-0.10.md) — consumer-facing migration path.
- [`../release/v0.6.0-release-notes.md`](../release/v0.6.0-release-notes.md) — reconstructed notes for the one release that had none.
- [ADR-062](./ADR-062-flow-step-identity-on-resume.md) — the `FlowSnapshot` change the gate flags.
- [ADR-006](./ADR-006.link.md) — The Wall, whose constraint makes the from-git approach possible.

## Engineering Protocol

1. A maturity change in `docs/stability-matrix.md` and the matching change in
   `tools/spi-api-diff/stability-surfaces.conf` land in the **same commit**. A PR moving one without
   the other is incomplete.
2. A new SPI class gets a label in the same PR that introduces it, and a matrix row wherever the
   matrix describes its surface. The `--verify-surfaces` step enforces this per class. **Do not
   satisfy a report from it by widening a class entry to its package** — inside a `mixed` package
   that re-opens defect 3 above, silently.
3. When the gate fails, respond with one of the three documented outcomes and record which. Do not
   silence it by demoting a surface without a note in the release notes explaining the demotion.
4. `docs/release/spi-api-history.md` is generated. Regenerate it at each release cut rather than
   editing it; the command is in its header.
5. Release notes for any version carrying a `preview` incompatibility name it explicitly. Ungated is
   not unrecorded.

## Amendments

Each amendment is marked in place at the text it changes, not rewritten (`adr-conventions.md`
rule 7). This section indexes them.

### A1 — 2026-10-07, ACCEPTED 2026-10-08: release candidates and the SPI freeze before 1.0.0

Tracked in [#614](https://github.com/exeris-systems/exeris-kernel/issues/614). v0.13.0 is the last
minor before 1.0.0, and the release path carries no release candidate as it stands: the release
workflow's strict tag check accepts only `^v[0-9]+\.[0-9]+\.[0-9]+$`, the release-integration skill
opens `development/X.(Y+1).0` after every release, and the branch-and-release policy says nothing
about a candidate or a freeze. This gate is the part of the release path a candidate changes the
meaning of, so the ruling is recorded here. A1.1 was ruled with the proposal; A1.2 and A1.4 were
ruled on 2026-10-08, each taking the option it recommended.

#### A1.1 — What a release candidate is (ruled)

- **Tag shape.** A release candidate is the tag `vX.Y.Z-RCn`: an upper-case `RC` and a decimal
  `n` from 1, matching `^v[0-9]+\.[0-9]+\.[0-9]+-RC[0-9]+$`. The first planned use is
  `v1.0.0-RC1`. The release workflow's strict check accepts that shape and no other suffix:
  `v0.12.0-rc1`, `v0.11.0-preview` and every other suffixed tag stay refused, exactly as today.
- **Distribution.** A candidate is published to Maven Central through the same `release` profile
  as a release. That profile already configures `central-publishing-maven-plugin` with
  `autoPublish=false` and `waitUntil=validated`, so the workflow uploads a deployment that is
  published by hand in the portal, or dropped. A candidate runs every gate a release runs, and the
  existing check that the tag's version equals the `<version>` of the root `pom.xml` at the tagged
  commit applies unchanged: the commit tagged `v1.0.0-RC1` carries `1.0.0-RC1` in its poms.
- **Ordering.** Maven orders `1.0.0-RC1` below `1.0.0-RC2` and both below `1.0.0` (measured with
  `maven-artifact` 3.9.16 `ComparableVersion`), so a consumer resolving the newest version never
  prefers a candidate over the release.
- **Permanence.** A published candidate cannot be withdrawn from Central. A defect found in `RCn`
  is fixed in `RC(n+1)`, never by re-tagging.
- **A candidate is never the gate's baseline.** The baseline resolver in `maven.yml` ("Diff SPI
  against the last released version") keeps its strict `^v[0-9]+\.[0-9]+\.[0-9]+$` filter, so it
  skips every `-RCn` tag. From the first candidate until `v1.0.0`, the SPI on the 1.0 line is
  compared with the last release tag (`v0.13.0` once it exists), not with the newest candidate.
  Diffing against a candidate would make whatever `RC1` changed the reference for `RC2`: the freeze
  would follow the candidates instead of holding them to the last release.
- **The generated record has no candidate rows.** `docs/release/spi-api-history.md` records release
  transitions only, so the row that follows `0.13.0` is `0.13.0 → 1.0.0`.

#### A1.2 — What the freeze covers (ruled 2026-10-08: option 1)

Options considered:

1. **Stable surfaces only (ruled).** From the first candidate, a binary-incompatible change
   to a `stable` surface fails the build, enforced by today's `--fail-on-stable` against the last
   release tag (A1.1). `preview` and `experimental` surfaces may still change after a candidate:
   such a change is reported in the diff and named in the next candidate's release notes
   (Engineering Protocol 5), and is not gated.
2. **Stable and preview surfaces.** A new `spi-api-diff.sh` mode fails on a binary-incompatible
   change to a `preview` surface as well, enabled on the 1.0 line from the first candidate. Today
   the script reports a `preview` break as "Reported, not gated".

Ruled: option 1. It is ruling 2 of this ADR applied unchanged, and it needs no new tool
mode, so the freeze runs on the gate that has already been exercised against the full release
history. Freezing `preview` would turn a label that promises change into one that forbids it for
the candidate period, a promise 1.0.0 itself does not make. The maturity labels are read from the
checked-out tree's `tools/spi-api-diff/stability-surfaces.conf` (`SURFACES_CONF` in the script), so
a surface relabelled `stable` on the 1.0 line is gated against its shape at the last release tag
from the commit that relabels it; a surface promoted to `stable` for 1.0.0 is therefore frozen from
its promotion, not only from the first candidate. Option 2 is the choice if candidate consumers are
expected to build against `preview` surfaces and churn between candidates would cost them more than
the new mode costs to build and test.

Neither option covers behavioural compatibility, which stays outside this gate (§"What is NOT in
scope").

#### A1.3 — The line after 0.13.0

The development line opened after the 0.13.0 release is `development/1.0.0` at `1.0.0-SNAPSHOT`,
not `development/0.14.0`. The base resolver the skill and the branch-and-release policy name,
`git branch -r --list 'origin/development/*' | sort -V | tail -1`, orders `development/1.0.0`
after `development/0.13.0` (measured with GNU `sort -V`), so it returns the 1.0 line without
change. Every candidate for 1.0.0 is cut from `development/1.0.0`'s state, and the line stays open
after a candidate: unlike an integrated `development/X.Y.0`, it is not done until `v1.0.0`.

#### A1.4 — How a candidate reaches its tag (ruled 2026-10-08: option 1)

The release workflow uploads only for a tag whose commit is on `main` (the `compare` check:
`identical` or `behind`), and the release ritual bumps the poms on `main` and tags that commit.
Options considered:

1. **A candidate goes through the release ritual (ruled).** A `release(1.0.0-RCn)` pull
   request integrates `development/1.0.0` into `main` with the poms at `1.0.0-RCn`; the tag goes on
   that commit on `main`. The `main`-containment check is unchanged, and `main` holds exactly what
   Central holds. Cost: `main` carries a candidate version between a candidate and the release, and
   `development/1.0.0` stays at `1.0.0-SNAPSHOT` throughout.
2. **A candidate is tagged on `development/1.0.0`.** The containment check accepts that branch for
   `-RCn` tags only. Cost: a second rule in the one guard that decides which commit can reach
   Central, and a pom bump and revert on the development line for each candidate.

Ruled: option 1, because the guard that matters keeps a single rule and the candidate is
produced by the same steps the release will be.

#### A1.5 — Obligations of the release that carries this amendment

This amendment changes no workflow, skill or policy. The changes it requires are obligations of
the release pull request that carries the first candidate's path, planned for the 0.13.0 release:

- `.github/workflows/release.yml` — the strict tag check accepts `-RC[0-9]+` as in A1.1, and its
  comment states which suffixes are refused; the containment check follows the A1.4 ruling.
- `.github/workflows/maven.yml` — the baseline filter is unchanged; its comment names `-RCn` tags as
  deliberately skipped.
- `.agents/skills/exeris-release-integration/SKILL.md` — step 7 opens `development/1.0.0` at
  `1.0.0-SNAPSHOT` after 0.13.0; the candidate flow (A1.4) and the exception to step 8 (the 1.0 line
  stays open after a candidate) are written down.
- `.agents/policies/branch-and-release.md` — states the candidate tag shape, the freeze scope ruled
  in A1.2, and the 1.0 line.
- The ecosystem ADR index row for ADR-065 shows the amendment date now that A1 is accepted.
