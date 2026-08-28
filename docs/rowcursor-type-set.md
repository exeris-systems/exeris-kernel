# The `RowCursor` type set

The set `AbstractRowCursorTck` asserts, fixed by [ADR-080](adr/ADR-080-rowcursor-value-contract.md).

**Every expectation here was measured against a running server, not read out of the PostgreSQL
sources.** 79 expressions against PostgreSQL 17 (`server_version_num` 170010), each run twice through
one connection — once through the extended-query protocol with binary results decoded client-side,
once through the simple-query protocol, which always returns text. That second answer is the server's
own `<type>_out`, and it is also what the Community driver returns, because pgjdbc's text path passes
those bytes through untouched. Zero mismatches on the implemented set.

This file is open-core because its content is **server behaviour**, not any driver's internals. What
measured it is not the subject; what PostgreSQL renders is.

## Do not use `::text` as the oracle

`::text` resolves a `pg_cast` entry, not `<type>_out`, and it disagrees exactly where a driver is
most likely to be wrong:

| Type | Server `_out` — the expectation | Via `::text` — wrong |
|---|---|---|
| `bool` | `t` | `true` |
| `bpchar(10)` from `'abc'` | `abc` + 7 spaces | `abc` |

A TCK built on `::text` fails a conformant driver. It would manufacture the divergence it exists to
detect.

## Preconditions

Not tuning. None of the expectations below is well-defined unless the session is pinned, and a driver
that cannot state its session zone cannot be asserted against `timestamptz` at all. The TCK either
pins the session itself or requires the provider to declare it.

| Parameter | Required | Undefined without it |
|---|---|---|
| `client_encoding` | `UTF8` | `text_send` emits the datum in the *session* encoding — so every character-family expectation |
| `TimeZone` | the driver's render zone | `timestamptz`, and anything containing one |
| `DateStyle` | `ISO` | every `date` and `timestamp` expectation |
| `IntervalStyle` | `postgres` | every `interval` expectation |
| `extra_float_digits` | ≥ 1 | float digit counts (PG ≥ 12) |
| `bytea_output` | `hex` | the `bytea` expectation |

## Tier A — must

Deterministic once the preconditions hold, and reachable by any CRUD application. A driver that fails
one of these is not swappable. Each row states why it is in the set: without that column the set is a
bag of types.

| Type | OID | Seed | Expected | Why this row earns its place |
|---|---:|---|---|---|
| `int2` | 21 | `12345` | `12345` | `0x3039` is the two ASCII bytes `'0'` `'9'` — the best single detector of a driver inferring format from the byte pattern |
| `int4` | 23 | `808464432` | `808464432` | `0x30303030` — the same trap one width up |
| `int8` | 20 | `9223372036854775807` | `9223372036854775807` | boundary; also catches a 32-bit read |
| `oid` | 26 | `4294967295` | `4294967295` | **unsigned** — a signed read returns `-1` |
| `bool` | 16 | `true` | `t` | not `true`; the value most likely to be written from intuition |
| `numeric` | 1700 | `1.50::numeric(10,2)` | `1.50` | the trailing zero is display scale, not noise |
| `numeric` | 1700 | `0.0000001` | `0.0000001` | `BigDecimal.toString` says `1E-7` — catches a driver routing through it |
| `numeric` | 1700 | `'NaN'` | `NaN` | the sign word carries no digits |
| `float4` | 700 | `1e6` | `1e+06` | `float4` and `float8` have **different** fixed/scientific cutoffs |
| `float8` | 701 | `1e6` | `1000000` | the same value, the other side of that cutoff — this pair is the whole point |
| `float8` | 701 | `0.1 + 0.2` | `0.30000000000000004` | shortest round-trip |
| `float8` | 701 | `'Infinity'` | `Infinity` | capitalised, unlike the temporal sentinels |
| `text` | 25 | non-ASCII + astral | verbatim | UTF-8 round trip through whatever buffer the driver reuses |
| `varchar` | 1043 | `'v'::varchar(10)` | `v` | no padding |
| `bpchar` | 1042 | `'abc'::char(10)` | `abc` + 7 spaces | padding **is data** — a driver that trims looks tidier and is wrong |
| `bytea` | 17 | `'\x48656c6c6f'` | `\x48656c6c6f` | lowercase hex behind the `\x` prefix |
| `bytea` | 17 | `''` | `\x` | the empty case still carries the prefix |
| `uuid` | 2950 | canonical | lowercase 8-4-4-4-12 | 16 raw bytes, no endian swap |
| `date` | 1082 | `'2000-01-01'` | `2000-01-01` | the PostgreSQL epoch — an off-by-30-years bug renders plausibly |
| `date` | 1082 | `'infinity'` | `infinity` | lowercase, unlike the float sentinel |
| `time` | 1083 | `'14:30:00.5'` | `14:30:00.5` | fraction stripped of trailing zeros, not `.500000` |
| `time` | 1083 | `'14:30:00'` | `14:30:00` | fraction **omitted entirely** when zero |
| `timestamp` | 1114 | `'1999-12-31 23:59:59.999999'` | verbatim | pre-epoch: `/` and `%` instead of `floorDiv`/`floorMod` lands one second late |
| `json` | 114 | `'{ "a" : 1 }'` | verbatim | whitespace preserved — `json` is stored as text |
| `jsonb` | 3802 | `'{"b":2,"a":1}'` | `{"a": 1, "b": 2}` | server-normalised, and carries a leading version byte on the wire |

## Tier B — gated

Correct and worth asserting; each needs a stated precondition or a server-version gate.

| Type | OID | Seed | Expected | Gate |
|---|---:|---|---|---|
| `timestamptz` | 1184 | `'2000-01-01 00:00:00+00'` | session zone | Assert against the pinned `TimeZone`, or assert the round trip back to the original instant. Include a January **and** a July instant in a DST zone — a raw-offset implementation passes one and fails the other |
| `timetz` | 1266 | `'14:30:00+05:30'` | `14:30:00+05:30` | The wire zone is seconds **west** of UTC and the printed offset is its negation; a sign error yields a plausible string off by twice the offset |
| `interval` | 1186 | `'1 year -1 day'` | `1 year -1 days` | `IntervalStyle=postgres`. Note the plural on `-1` |
| `interval` | 1186 | `'-1 year 3 days'` | `-1 years +3 days` | The `+` latch: `interval_out` signs a positive part following a negative one. Nothing else in the type space exercises it |
| `numeric` | 1700 | `'Infinity'` | `Infinity` | PG ≥ 14 |
| `interval` | 1186 | `'infinity'` | `infinity` | PG ≥ 17 |
| `float4` / `float8` | 700 / 701 | — | shortest round-trip | PG ≥ 12; below it the server emits `DBL_DIG+3` digits |
| `name` | 19 | `'nm'` | `nm` | Use the DataRow length, never the declared 64-byte width, or NUL padding appears |
| `xml` | 142 | `'<a>1</a>'` | verbatim | Requires a server built with libxml |
| `"char"` | 18 | `'x'` | `x` | One byte, not `char(1)`. Easy to confuse with `bpchar` |

## Tier C — policy

**No tier implements these, and that is the point.** Every entry has a perfectly good `<type>_out`
form the server will produce, and every one arrives as a structured binary datum a naive driver
mis-decodes into mojibake. The assertion is not the rendered value — it is that **for a type the
driver does not implement, `getString` fails with a typed exception rather than returning one**.
That is testable without implementing a single one of them, and it is what closes the
silent-corruption class permanently.

| Type | OID | Server `_out` | Note |
|---|---:|---|---|
| `int4[]` | 1007 | `{1,2}` | Arrays are the largest real gap — an ordinary application type |
| `text[]` | 1009 | `{a,b}` | `array_out` quoting is a sub-algorithm of its own |
| `uuid[]` | 2951 | `{a0eebc99-…}` | |
| `numeric[]` | 1231 | `{1.5}` | |
| `enum` | user | `happy` | `enum_send` is a text passthrough, so a naive UTF-8 wrap is *accidentally correct* — which is exactly why a driver must not guess by OID range |
| composite | user | `(1,a)` | Shares the user OID range with enum and has genuinely binary send |
| `int4range` | 3904 | `[1,5)` | Same range, same hazard |
| `int4multirange` | 4451 | `{[1,5)}` | PG ≥ 14 |
| `inet` / `cidr` | 869 / 650 | `192.168.0.1/24` | |
| `macaddr` | 829 | `08:00:2b:01:02:03` | |
| `bit` / `varbit` | 1560 / 1562 | `1011` | |
| `tsvector` | 3614 | `'a' 'b'` | |
| `pg_lsn` | 3220 | `0/16B3748` | |

### Domains are not in this tier

Measured: PostgreSQL reports the **base type OID** in `RowDescription`, so a domain over `int4`
arrives as OID 23 and one over `text` as OID 25. They render correctly with no special handling.

A row for each is worth having precisely to pin that — the opposite belief is easy to hold, and it
would justify an OID-range heuristic that silently corrupts ranges and composites, which share the
user range and have genuinely binary send functions.

## Tier D — out

Written down so they are not re-litigated.

| Type | Why not |
|---|---|
| `money` | `$12.34` — depends on `lc_monetary`, which is not a session parameter a driver can pin portably |
| `regclass`, `reg*` | Depends on the catalog and `search_path`; the same OID renders differently in two databases |
| `point`, `box`, `line`, `path`, `polygon`, `circle` | Their text form embeds float rendering, so a failure cannot be attributed — it is a float bug wearing a geometry costume |
| `tstzrange` | Contains a `timestamptz` and inherits its zone dependence; if included, gate it exactly as Tier B does |
| `xid`, `cid` | Internal; they render as unsigned integers and add nothing the `oid` row does not already cover |

## Beyond `getString`

The type set is half the widening. The divergence that motivated ADR-080 was reachable through
**five other accessors** that read a fixed width at the column offset regardless of the declared
type. For every Tier A row, the widened TCK asserts:

1. **`getString` works on every type** — the JDBC-shaped contract a driver-agnostic application
   relies on, and the one assertion that makes the tiers comparable at all.
2. **The matching typed accessor agrees** — `getInt` on `int4`, `getBoolean` on `bool`,
   `getInstant` on `timestamptz`.
3. **A mismatched typed accessor obeys ADR-080 §3** — `getInt` on a two-byte `int2` must not read
   four bytes and return `809500672`. Lossless widening is accepted; lossy conversion throws.
4. **SQL NULL returns `null` for every reference-typed accessor**, resolved before any type dispatch.
5. **The undescribed-column path is declared** — a supported state, or a programming error.

## Using this file

A cross-driver TCK cannot use the simple-query protocol — that is available to a harness testing one
driver against its own server, not to a contract test comparing tiers. The expectations here are
therefore the fixed strings above.

Changing an expectation in this file is a change to ADR-080, not a test edit.
