# SPI API compatibility history

**Status:** Generated artefact — do not edit by hand
**Regenerate:** `tools/spi-api-diff/spi-api-diff.sh --history v0.5.0,v0.6.0,…,v0.10.2 --out docs/release`
**Method:** each tag's `exeris-kernel-spi` is compiled straight from git and diffed with
[japicmp](https://siom79.github.io/japicmp/) at `public` visibility; findings are classified by the
maturity label declared in [`../stability-matrix.md`](../stability-matrix.md).

---

## Why this file exists

The stability matrix states an *intent*: which SPI surfaces are settled and which are still moving.
An intent that is never measured is a claim, not a property. This file is the measurement — one row
per release transition, produced by a script rather than by review, so that "we did not break the
contract" is something a consumer can check instead of something they have to believe.

The same script runs as a build gate: a binary-incompatible change to a surface declared `stable`
fails CI. See §"How to read a failure" below.

---

## Per-release record

`stable` counts changes to surfaces the matrix declares `stable`; `preview` counts `preview` and
`experimental` together. Counts are **affected top-level classes**, not individual removed members.
"Semver" is japicmp's own suggestion from the bytecode, independent of the version actually shipped.

| Transition | Semver suggestion | `stable` breaks | `preview` breaks | Verdict |
|---|---|---|---|---|
| `v0.5.0` → `v0.6.0` | `1.0.0` | **7** | 0 | breaking — §1 |
| `v0.6.0` → `v0.7.0` | `0.1.0` | 0 | 0 | additive |
| `v0.7.0` → `v0.7.1` | `0.0.1` | 0 | 0 | no API change |
| `v0.7.1` → `v0.8.0` | `0.1.0` | 0 | 0 | additive |
| `v0.8.0` → `v0.8.1` | `0.1.0` | 0 | 0 | additive on a patch line — see note |
| `v0.8.1` → `v0.9.0` | `1.0.0` | **1** | 0 | breaking — §2 |
| `v0.9.0` → `v0.10.0` | `1.0.0` | 0 | 2 | breaking on `preview` only — §3 |
| `v0.10.0` → `v0.10.1` | `0.0.1` | 0 | 0 | no API change |
| `v0.10.1` → `v0.10.2` | `0.0.1` | 0 | 0 | no API change |

### The declaration line

The stability matrix was **first published in v0.9.0**. That splits the table in two, and the split
matters more than the raw counts:

- **Before v0.9.0** there was no published maturity declaration. The breaks in §1 and §2 were not
  violations of anything — they predate the promise. They are migration archaeology, nothing more.
- **From v0.9.0 onward** the declaration exists and has been kept. The one breaking transition in
  that window, `v0.9.0 → v0.10.0`, lands entirely on `eu.exeris.kernel.spi.events`, which the matrix
  labels `preview` — where the policy explicitly permits it. **Zero `stable` surfaces have taken a
  binary-incompatible change since the declaration was published.**

That is the claim this file exists to support, and it is checkable: re-run the command at the top.

### Note on `v0.8.0` → `v0.8.1`

A patch release that carried an API addition (japicmp suggests a minor bump). Nothing broke, so no
consumer was affected, but it is a semver irregularity of the kind this gate now surfaces at release
time rather than in a later audit.

---

## §1 — `v0.5.0` → `v0.6.0`

Capability descriptors removed; dialect selection pushed out of the SPI (The Wall).

```
---! REMOVED INTERFACE: eu.exeris.kernel.spi.persistence.DatabaseDialect
---! REMOVED CLASS:     eu.exeris.kernel.spi.persistence.DatabaseDialect$ImmutableDatabaseDialect
---! REMOVED CLASS:     eu.exeris.kernel.spi.persistence.PersistenceEngineCapabilities
---! REMOVED CLASS:     eu.exeris.kernel.spi.transport.TransportEngineCapabilities
***! MODIFIED INTERFACE: eu.exeris.kernel.spi.persistence.PersistenceEngine
       ---! REMOVED METHOD: PersistenceEngineCapabilities capabilities()
***! MODIFIED INTERFACE: eu.exeris.kernel.spi.persistence.PersistenceProvider
       ---! REMOVED METHOD: DatabaseDialect dialect(PersistenceConfig)
***! MODIFIED INTERFACE: eu.exeris.kernel.spi.transport.TransportEngine
       ---! REMOVED METHOD: TransportEngineCapabilities capabilities()
```

Migration: [`upgrade-0.5-to-0.10.md`](./upgrade-0.5-to-0.10.md) §1.
This release shipped without release notes; they were reconstructed later as
[`v0.6.0-release-notes.md`](./v0.6.0-release-notes.md).

## §2 — `v0.8.1` → `v0.9.0`

One rename on a telemetry carrier, aligning it with Glass-Box terminology.

```
***! MODIFIED CLASS: eu.exeris.kernel.spi.telemetry.TelemetryConfig
       ---! REMOVED METHOD: long blackBoxOffHeapBytes()      → glassBoxOffHeapBytes()
```

Migration: [`upgrade-0.5-to-0.10.md`](./upgrade-0.5-to-0.10.md) §2.

## §3 — `v0.9.0` → `v0.10.0` (`preview` surface)

Events log-ordering and OCC boundary (ADR-049) and binding-agnostic topic (ADR-050).

```
***! MODIFIED INTERFACE: eu.exeris.kernel.spi.events.EventStreamAppender
       ---! REMOVED METHOD: void append(StreamId, EventDescriptor, EventPayload)
***! MODIFIED CLASS: eu.exeris.kernel.spi.events.EventTypeSpec
       ---! REMOVED CONSTRUCTOR: EventTypeSpec(String, int, boolean, boolean)
```

Both on `eu.exeris.kernel.spi.events` — `preview`, so permitted without a major bump. The
`EventTypeSpec.of(...)` / `ofPersistent(...)` factories were preserved; only direct constructor
calls break. Migration: [`upgrade-0.5-to-0.10.md`](./upgrade-0.5-to-0.10.md) §3.

---

## How to read a failure

The gate fails when a surface labelled `stable` changes incompatibly. That is a prompt for a
decision, not an instruction to revert:

1. **Unintended?** Restore compatibility. For a record that gained a component, keeping the previous
   canonical constructor as an explicit overload is usually enough.
2. **Intended, and the surface was mislabelled?** Demote it in [`../stability-matrix.md`](../stability-matrix.md)
   *and* `tools/spi-api-diff/stability-surfaces.conf` in the same commit, and say why in the release
   notes. The matrix is allowed to be wrong; it is not allowed to be quietly wrong.
3. **Intended, and the surface really is stable?** Then it is a major-version decision, and pre-1.0
   it needs an ADR — not a build-config edit.

The gate also fails when an SPI package exists with no maturity label at all
(`spi-api-diff.sh --verify-surfaces`), which is how a new subsystem is stopped from shipping
unclassified.
