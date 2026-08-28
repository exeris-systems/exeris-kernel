# ADR-080: `RowCursor` states what it returns, and refuses what it cannot

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-08-28                                                                                |
| **Scope**       | `kernel/persistence`                                                                      |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-08-28](../rfc/RFC-2026-08-28-rowcursor-string-contract.md); v0.12               |
| **Compliance**  | [docs/subsystems/persistence.md](../subsystems/persistence.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

`RowCursor.getString(int)` had no stated contract. Two tiers implemented it and answered differently
for column types outside the obvious text set, and nothing in the repository said which was right.

The absence is wider than one method. Across `RowCursor`'s thirteen accessors, **eleven declare no
`@throws` at all** — `getInt` and `getSegment` are the only two that do. So `getString`'s silence is
not a decision expressed by omission; it is an unwritten contract that happens to have been noticed
at the one method where the tiers diverged. The executable contract is silent in the same place:
`AbstractRowCursorTck` asserts three types and checks `getString` on a single column whose SQL type
each binding chooses for itself.

`spi.persistence` is classified **stable since 0.5.0**. What makes this a decision rather than a
patch is that the divergence at issue — a total function narrowed to a partial one — is **invisible
to the SPI API-diff gate**, which compares signatures. A method that starts throwing has the same
one. Nothing would have caught this, and nothing will catch the next instance, until the contract is
written and executable.

The promise at stake is the open-core one: swap the driver, keep the application.

## Decision

### 1. The three obligations every accessor now carries

Each `RowCursor` accessor states, in its Javadoc and in the TCK:

- **NULL behaviour** — resolved before any type dispatch. A reference-typed accessor returns `null`
  for SQL NULL and must not throw; a primitive accessor throws, because it has no null to return.
- **Out-of-range behaviour** — `IndexOutOfBoundsException`, uniformly. `getInt` already said so and
  ten neighbours behaved identically without saying it.
- **Type domain**, for the converting accessors — what the accessor accepts, and what it does
  outside that set.

The first two are transcription: the tiers already agree, and writing them down costs nothing but
closes the reading that `getString`'s silence invited. Only the third is a ruling.

### 2. `getString` is total over a measured set, and refuses outside it

`getString` returns the server's `<type>_out` rendering for every type in **Tier A** and **Tier B**
of the measured type set, and throws a typed exception for any type it does not implement.

It **does not** decode unknown bytes as UTF-8. Returning mojibake for a structured binary datum is
the silent-corruption class this decision exists to close, and a refusal is strictly better than a
plausible wrong answer on a data path.

The type set is not enumerated in this ADR by transcription. It is
[`docs/rowcursor-type-set.md`](../rowcursor-type-set.md) — carried in this repository because
the TCK that asserts it lands here, and because its content is server behaviour rather than any
driver's internals. Its authority rests on how it was produced: **79 expressions measured against a running PostgreSQL 17, each run twice through one
connection** — once extended-binary, once simple-query — with the server's own answer recorded as
the expectation. Not reconstructed from `<type>_out` sources.

Three properties of that set are load-bearing here rather than incidental:

- **Tier C is a policy tier, not a backlog.** Its thirteen entries are types no tier implements, and
  the assertion is not "render it" but *"an unimplemented type fails with a typed exception rather
  than returning a value"*. That is testable without implementing any of them, which is what makes
  the refusal contract enforceable on day one rather than aspirationally.
- **`enum` is the trap that names the rule.** `enum_send` is a text passthrough, so a naive UTF-8
  wrap is *accidentally correct* — and a driver that generalises from that to an OID-range heuristic
  silently corrupts ranges and composites, which share the user OID range and have genuinely binary
  send functions. The contract is therefore **per declared type, never per OID range**.
- **Domains are not in Tier C.** Measured: PostgreSQL reports the *base* type OID in
  `RowDescription`, so a domain over `int4` arrives as OID 23. They need no handling — and a row
  pinning that is worth having precisely because the opposite belief is what would justify the OID
  heuristic above.

### 3. A mismatched typed accessor widens where widening is lossless, and throws where it is not

This is the half the originating report did not reach, and it is the same hazard one layer down: the
divergence was reachable through **five other accessors that read a fixed width at the column offset
regardless of the declared type**. `getInt` on a two-byte `int2` reading four bytes returns
`809500672` — a plausible number, silently wrong. Neither the Javadoc nor the TCK said whether that
call should widen or throw.

**Ruling:** a typed accessor accepts any column type it can represent **without loss**, and throws
otherwise. `getInt` on `int2` returns `12345`; `getInt` on an out-of-range `int8` throws.

The rule is a transcription of Community's shipped behaviour rather than a new invention — JDBC's
`getInt` widens `SMALLINT` and pgjdbc refuses an out-of-range `BIGINT` — which is what keeps it from
retracting a published guarantee. Naming it matters because the *enterprise* tier had no reason to
land on it by itself: reading a fixed width at an offset is the natural implementation, and it is
wrong in the direction that returns a number.

### 4. The TCK's oracle, and its preconditions

Two constraints that decide whether a widened TCK means anything:

**`::text` is prohibited as the oracle.** It resolves a `pg_cast` entry, not `<type>_out`, and it
disagrees exactly where a driver is most likely to be wrong: `bool` casts to `true` where `boolout`
gives `t`, and `bpchar::text` trims the padding `bpcharout` preserves. **A TCK built on `::text`
fails a conformant driver** — it would have manufactured the divergence it exists to detect.

**Six session parameters are preconditions, not tuning:** `client_encoding=UTF8`, a pinned
`TimeZone`, `DateStyle=ISO`, `IntervalStyle=postgres`, `extra_float_digits ≥ 1`,
`bytea_output=hex`. Without them no expectation in the set is well-defined, and a driver that cannot
state its session zone cannot be asserted against `timestamptz` at all. The TCK either pins the
session itself or requires the provider to declare it.

**A cross-driver TCK cannot use the simple-query protocol.** The measurement harness may, because it
tests one driver against its own server; a contract test compares tiers, so its expectations are the
fixed strings in the set.

### 5. What the widened TCK asserts

For every Tier A row: `getString` renders it; the matching typed accessor agrees; a mismatched typed
accessor obeys §3; SQL NULL returns `null` for every reference-typed accessor; and the
undescribed-column path is declared rather than incidental. Tier B rows carry their stated gate.
Tier C asserts the refusal only.

### 6. The refusal is typed

An unimplemented type raises a `PersistenceProviderException` carrying a new error code,
**`EX-PERS-5008`**, registered in `KernelErrorCodes` by the implementing change with its `rawArgs`
layout. The message names the column index and the declared type OID and nothing else — a value the
driver could not decode is not a value it should put in a message.

`EX-PERS-5008` is the next free code in the persistence family at the time of writing; the
implementing PR confirms that rather than assuming it.

## Consequences

**Both tiers change.** Community must refuse Tier C types where the JDBC driver would happily produce
something — that is the price of the contract being the same on both sides, and it is the one place
this decision takes away a behaviour that works today. It is deliberate: a Community application
reading a native `enum` column through `getString` gets a value that the enterprise tier cannot
promise, and leaving that working is what made the tiers non-swappable in the first place.

**Nothing in the kernel's own persistence is affected.** `JdbcFlowSnapshotCodec` reads `FlowState`
through `getString`, but `V0.7.0__create_saga_state.sql` declares that column `TEXT` — Tier A. The
kernel's own idiom is enum-as-`TEXT`, and this ADR does not change it.

**The stability matrix gains a behavioural note.** A contract this specific on a `stable` surface
needs the matrix to say that `spi.persistence`'s guarantee now includes value semantics, not only
signatures — because the API-diff gate cannot see the difference and a reader would otherwise assume
it could.

**Sequencing.** The TCK widening lands in `exeris-kernel` 0.12; the enterprise tier consumes it in
its own 0.7 line. The refusal contract (§2 Tier C, §6) is enforceable from the first TCK run, since
it needs no type implemented.

## Alternatives Considered

**Total everywhere — every type stringified.** Rejected because "every type" has no floor: the
enterprise tier would need a rendering path for types it has no reason to implement, and for the
ones with no meaningful text form it is back to throwing. The escape is needed either way, which
makes this option the accepted one with the refusal left undeclared.

**Partial with a declared type set, throwing outside it.** This is what the enterprise tier already
did, and it is honest about what a zero-copy decoder promises. Rejected **as stated** because it
retracts Community's totality without saying where the line is — the accepted decision is this
option with the line measured and written down, which is the difference between a narrowing and a
contract.

**Leave it unwritten.** Rejected: the swap-transparency claim quietly stops being true for one
method, with no mechanism to find the next. Eleven of thirteen accessors are equally unspecified, so
"the next one" is a prediction, not a hypothetical.

## Compliance and Verification

- `AbstractRowCursorTck` widened per §5; every provider binding compiles against it or fails.
- `docs/subsystems/persistence.md` carries the value contract; `docs/stability-matrix.md` carries the
  behavioural note from §Consequences.
- `EX-PERS-5008` registered in `KernelErrorCodes` with its `rawArgs` layout.
- The measured set is versioned as `docs/rowcursor-type-set.md`; a change to an expectation is a
  change to this ADR, not a test edit.
