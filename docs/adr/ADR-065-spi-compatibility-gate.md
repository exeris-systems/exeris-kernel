# ADR-065: The SPI stability declaration is machine-enforced

| Attribute       | Value                                                                                    |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                             |
| **Deciders**    | Arkadiusz Przychocki                                                                     |
| **Date**        | 2026-08-05                                                                               |
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

### 3. An unclassified SPI package fails the build

`--verify-surfaces` fails when a package exists in the SPI tree with no maturity label at all.
Classification is mandatory, not opt-in — an unlabelled surface is one the gate cannot protect and a
consumer cannot reason about.

This was not a theoretical guard. Its first run found `spi.scheduling` and `spi.storage.blob`
shipping on the 0.11 line with accepted ADRs (057 / 056), `Abstract*Tck` coverage and Community
bindings — and no row in the matrix. Both are now labelled `preview`.

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
  an assurance. Two such defects appeared while building it — japicmp separates include expressions
  with `;`, so a comma-separated list matches nothing and reports clean; and an SPI revision that
  fails to compile yields an empty jar that compares as unchanged. Both are now asserted against
  (`assert_filter_selects`, `assert_jar`, and a required comparison header), and both are the reason
  those assertions exist rather than defensive decoration.
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
2. A new SPI package gets a matrix row and a label in the same PR that introduces it. The
   `--verify-surfaces` step enforces this; do not work around it by widening an existing package
   expression to swallow the new one.
3. When the gate fails, respond with one of the three documented outcomes and record which. Do not
   silence it by demoting a surface without a note in the release notes explaining the demotion.
4. `docs/release/spi-api-history.md` is generated. Regenerate it at each release cut rather than
   editing it; the command is in its header.
5. Release notes for any version carrying a `preview` incompatibility name it explicitly. Ungated is
   not unrecorded.
