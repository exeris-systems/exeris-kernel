# RFC-2026-08-28: What does `RowCursor.getString` promise, and over which column types?

|                   |                                                                                                |
|-------------------|------------------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                                      |
| **Author(s)**     | Arkadiusz Przychocki                                                                           |
| **Date Opened**   | 2026-08-28                                                                                     |
| **Date Closed**   | 2026-08-28                                                                                              |
| **Target ADR(s)** | ADR-080 (reserved)                                                                             |
| **Affected Repos**| `exeris-kernel` (authoritative), enterprise tier (stub)                                        |
| **Reviewers**     | —                                                                                              |

## Question

`RowCursor.getString(int)` has no stated contract. Two tiers implement it and answer differently for
column types outside the obvious text set, and nothing in the repository says which is right. This
RFC asks what the method promises — and, because the answer generalises, what `RowCursor` promises.

## Context

The method is part of `eu.exeris.kernel.spi.persistence`, classified **stable since 0.5.0** in
`docs/stability-matrix.md`. That classification is what makes this worth a decision rather than a
patch: a change here is a change to a published contract, and the kind of change at issue —
narrowing a total function to a partial one — is **invisible to the SPI API-diff gate**, which
compares signatures. `japicmp` will not notice a method that starts throwing.

It is also a cross-tier promise rather than an implementation detail. The open-core claim is that a
driver can be swapped without changing the application; what `getString` returns for a given column
is exactly the sort of thing that claim is made of.

## Investigation

### The contract is not merely thin — it is absent, and wider than one method

The originating report reads `getString`'s silence against `getInt`'s two `@throws` declarations in
the same file and concludes the absence is a decision. **That inference does not survive counting.**
Across `RowCursor`'s thirteen accessors:

| Accessor | `@throws` declared |
|---|---|
| `getInt` | `IndexOutOfBoundsException`, `NullPointerException` |
| `getSegment` | `NullPointerException` |
| `getLong`, `getShort`, `getFloat`, `getDouble`, `getBoolean`, `isNull`, `getLength`, `getString`, `getBytes`, `getUuid`, `getInstant` | **none** |

Eleven of thirteen declare nothing, including primitives that unambiguously throw the very
exceptions `getInt` names — an out-of-range column index does not behave differently for `getLong`
than for `getInt`. So `getString` is not the odd one out; **`getInt` is the one place someone
bothered.**

This matters for the decision. Reading `getString`'s silence as *"this one does not throw"*
over-reads it. Reading it as *"nothing is specified"* is correct — and that is the larger finding,
because it is thirteen methods wide rather than one. Whatever this RFC concludes about `getString`,
the fix is not a `@throws` line on one method.

### The executable contract is silent in the same place

`AbstractRowCursorTck` contains exactly one `getString` case — *"returns correct value (allocating
path)"* — asserting that column 2 equals an abstract `expectedString()`. The column's SQL type is
chosen by each binding's `testQuery()`, not by the contract. So the TCK pins that a text column
round-trips, and nothing else: not type coverage, not the unhandled-type behaviour, not whether the
function is total.

Three layers, one silence: the javadoc, the TCK, and — until the tiers diverged — anyone's
expectations.

### What each tier does today

**Community** (`JdbcQueryResult:292`) delegates to `ResultSet.getString(column + 1)`. Under JDBC that
is a **total** function over column types: PostgreSQL renders enums, `json`, arrays and most
everything else to text. Community therefore promises totality by inheritance, not by decision — it
never chose this, it got it from the driver underneath.

**The enterprise tier** decodes from off-heap bytes and, per the originating report, throws on a type
outside its handled set rather than returning bytes decoded as though they were UTF-8. That is the
defensible engineering choice in isolation: mis-decoded bytes are worse than a refusal. It is also a
narrowing of a total function to a partial one, and it was taken for one tier.

### The kernel's own usage locates the divergence

`JdbcFlowSnapshotCodec:208` reads a `FlowState` back as `FlowState.valueOf(row.getString(4))` — the
kernel is itself a `getString` consumer on an enum-shaped value, on the saga recovery path. The
column is `state TEXT NOT NULL` (`V0.7.0__create_saga_state.sql:20`), so the kernel round-trips its
enum through text and **both tiers agree on it**.

That is worth stating because it bounds the blast radius, and because the idiom is the kernel's own
recommendation in executable form: enum-as-`TEXT`. Nothing in the kernel's persistence depends on
`getString` handling a native `enum` type.

The divergence therefore lands on **application** schemas that use a native `enum`, `json`, or array
column and read it with `getString`. Those applications work on Community today, which is what makes
the type domain a decision rather than a clarification.

### Constraints the options must respect

1. **`spi.persistence` is `stable`.** A behavioural narrowing is a contract retraction even though
   the signature is unchanged, and the API-diff gate cannot see it.
2. **Community's totality is shipped.** Whatever it was inherited from, applications on 0.5.0+ can
   rely on it, and one of the options takes it away.
3. **Whatever is decided must become executable.** A `@throws` line that no TCK case exercises would
   reproduce the present situation with more words — this document's own subject.
4. **The Enterprise tier must not be forced to guess bytes.** Any option that requires it to decode
   an unknown type as UTF-8 trades a loud failure for a silent corruption, which is the wrong
   direction on a data path.

## Options Considered

### Option A — Total: `getString` stringifies every column type

The contract states that `getString` returns a string representation for any non-NULL column, and
every tier must satisfy it.

**Cost:** falls on the enterprise tier, which needs a decode path for types outside its fast set —
and the honest form of that is a fallback that asks the driver for a rendering, not a UTF-8
reinterpretation of arbitrary bytes. For types where no rendering exists the tier is back to
throwing, so the contract needs an escape anyway, which is Option C.

**Merit:** preserves every shipped Community behaviour; nothing an application does today stops
working.

### Option B — Partial: a declared type set, throwing outside it

The contract names the column types `getString` handles and states that anything else throws.

**Cost:** falls on **Community**, and this is the option's real price. Community is total today; a
declared type set retracts a shipped guarantee, and the applications it breaks are precisely those
reading native `enum` / `json` columns — the ones with no warning, because their code compiles
unchanged. Constraints 1 and 2 both bite here.

**Merit:** it is what the enterprise tier already does, and it is honest about what a zero-copy
decoder can promise.

### Option C — Total with a declared escape

`getString` is total over a named set (text, numeric, boolean, temporal, `uuid`, and the driver's
text-renderable types) and is stated to throw a **named, specified** exception outside it — with the
type named in the message — rather than throwing an unspecified one or decoding blindly.

**Cost:** both tiers. Community must learn to refuse where the JDBC driver would happily produce
something meaningless; the enterprise tier must produce the named exception rather than whatever it
throws now. The TCK grows a type-coverage case per binding.

**Merit:** it is the only option under which *the same program observes the same behaviour on both
tiers* — which is the promise at stake — without either tier being asked to guess bytes. It also
gives the divergence a name, so the next occurrence is a test failure rather than a report.

### Option D — Do nothing

Leave the contract unwritten and let each tier keep its behaviour.

**Cost:** the swap-transparency claim quietly stops being true for one method, and there is no
mechanism to discover the next method it stops being true for. Given that eleven of thirteen
accessors are equally unspecified, "the next one" is likely rather than hypothetical.

## Recommendation

**Option C, with the scope widened from `getString` to `RowCursor`.**

The narrow question — what does `getString` promise — cannot be answered well in isolation, because
the reason it has no answer is that eleven of its neighbours have none either. Fixing one method
leaves twelve instances of the same failure mode and no way to notice the next divergence.

Two things are worth separating in the ADR:

1. **The general rule.** Every `RowCursor` accessor states its NULL behaviour, its out-of-range
   behaviour, and — for the converting accessors — its type domain. This is documentation plus TCK
   cases, not a code change, for the accessors the tiers already agree on.
2. **The `getString` type domain**, which is the one place a tier has to change behaviour, and the
   only part that is a genuine decision rather than a transcription.

**Where the named type set gets fixed, and why not here.** Option C's set is proposed from
Community's behaviour and PostgreSQL's type system. It is deliberately not enumerated in this
document: a public open-core RFC is the wrong place to publish the closed tier's decoder internals,
and a set stated from one tier would be the same one-sided ruling this RFC exists to replace. The
enumeration belongs in ADR-080, written against both implementations.

### Decision

**Option C, scope widened to `RowCursor`** — accepted 2026-08-28, single-decider, per this
repository's accepted-on-merge convention. ADR-080 carries the ruling and the enumerated type
domain.

**Residual uncertainty worth stating.** Nobody has counted how many applications read a native
`enum` or `json` column through `getString`; the case against Option B rests on the shape of the
retraction rather than on a measured population. If that population is empty, B becomes cheaper than
this document prices it — but establishing that it is empty is not something the kernel can do for
its adopters.
