/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: the {@code RowCursor} type-set contract (ADR-080 §2, §3, §5).
 *
 * <h2>Why this is separate from {@link AbstractRowCursorTck}</h2>
 * <p>That TCK asserts what holds on any engine — NULL behaviour, column-index behaviour, flyweight
 * identity — and its bindings may use whatever database runs in the default build. This one asserts
 * the <em>rendering</em>, and rendering is server behaviour: the set in
 * {@code docs/rowcursor-type-set.md} was measured against PostgreSQL, and a driver that renders
 * {@code bool} as {@code TRUE} rather than {@code t} is not failing this contract so much as living
 * outside it. Only a binding whose engine ADR-080 measured extends this class.
 *
 * <h2>Preconditions are not tuning</h2>
 * <p>None of the expectations below is well defined unless the session is pinned. A binding either
 * accepts {@link #pinSession(PersistenceConnection)} as written or overrides it, but it must not
 * skip it — an unpinned session makes every temporal and float expectation a coin flip.
 *
 * @since 0.12.0
 */
public abstract class AbstractRowCursorTypeSetTck {

    /** Creates a bootstrapped engine against the measured server. */
    protected abstract PersistenceEngine createEngine();

    /**
     * Pins every session parameter the expectations depend on.
     *
     * <p>Not a default anyone should be comfortable inheriting silently — each line here is the
     * reason one column of the measured set has a fixed answer at all.
     */
    protected void pinSession(PersistenceConnection conn) {
        conn.executeUpdate("SET client_encoding TO 'UTF8'");
        conn.executeUpdate("SET TimeZone TO 'UTC'");
        conn.executeUpdate("SET DateStyle TO 'ISO'");
        conn.executeUpdate("SET IntervalStyle TO 'postgres'");
        conn.executeUpdate("SET extra_float_digits TO 1");
        conn.executeUpdate("SET bytea_output TO 'hex'");
    }

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Tier A — getString renders exactly what the server renders
    // =========================================================================

    /**
     * {@code {expression, expected}} — every row measured against the server, never reconstructed
     * from {@code <type>_out} sources. Each earns its place; see the type-set document for why.
     */
    private static final String[][] TIER_A = {
        // The two-ASCII-byte traps: 0x3039 is '0','9' and 0x30303030 is "0000".
        {"12345::int2", "12345"},
        {"808464432::int4", "808464432"},
        {"9223372036854775807::int8", "9223372036854775807"},
        // oid is unsigned — a signed read renders -1.
        {"4294967295::oid", "4294967295"},
        // Not "true". The value most likely to be written from intuition.
        {"true::bool", "t"},
        // The trailing zero is display scale, not noise.
        {"1.50::numeric(10,2)", "1.50"},
        // BigDecimal.toString says 1E-7 — this catches a driver routing through it.
        {"0.0000001::numeric", "0.0000001"},
        {"'NaN'::numeric", "NaN"},
        // float4 and float8 have different fixed/scientific cutoffs. This pair is the point.
        {"1e6::float4", "1e+06"},
        {"1e6::float8", "1000000"},
        {"(0.1::float8 + 0.2::float8)", "0.30000000000000004"},
        // Capitalised, unlike the temporal sentinels below.
        {"'Infinity'::float8", "Infinity"},
        {"'zażółć gęślą jaźń'::text", "zażółć gęślą jaźń"},
        {"'v'::varchar(10)", "v"},
        // Padding IS data. A driver that trims looks tidier and is wrong.
        {"'abc'::char(10)", "abc       "},
        {"'\\x48656c6c6f'::bytea", "\\x48656c6c6f"},
        // The empty case still carries the prefix.
        {"''::bytea", "\\x"},
        {"'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11'::uuid", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"},
        // The PostgreSQL epoch — an off-by-30-years bug renders plausibly.
        {"'2000-01-01'::date", "2000-01-01"},
        // Lowercase, unlike the float sentinel above.
        {"'infinity'::date", "infinity"},
        // Fraction stripped of trailing zeros, and omitted entirely when zero.
        {"'14:30:00.5'::time", "14:30:00.5"},
        {"'14:30:00'::time", "14:30:00"},
        // Pre-epoch: / and % instead of floorDiv/floorMod lands one second late.
        {"'1999-12-31 23:59:59.999999'::timestamp", "1999-12-31 23:59:59.999999"},
        // json is stored as text — whitespace survives.
        {"'{ \"a\" : 1 }'::json", "{ \"a\" : 1 }"},
        // jsonb is server-normalised, and carries a leading version byte on the wire.
        {"'{\"b\":2,\"a\":1}'::jsonb", "{\"a\": 1, \"b\": 2}"},
    };

    /**
     * Tier B — rendered, but each row needs a stated precondition or a server-version gate. The
     * gates are why these are a separate group: asserted carelessly they either fail on an older
     * server or, worse, pass against a session that happens to be pinned the way the author's was.
     *
     * <p>{@code timestamptz} is not here — it needs a zone the session default cannot provide, and
     * gets its own test below.
     */
    private static final String[][] TIER_B = {
        // The wire zone is seconds WEST of UTC and the printed offset is its negation. A sign error
        // yields a plausible string off by exactly twice the offset — 09:00:00-05:30 reads fine.
        {"'14:30:00+05:30'::timetz", "14:30:00+05:30"},
        // IntervalStyle=postgres. Note the plural on -1: "-1 days", not "-1 day".
        {"'1 year -1 day'::interval", "1 year -1 days"},
        // The + latch: interval_out signs a positive part that follows a negative one. Nothing else
        // in the type space exercises it.
        {"'-1 year 3 days'::interval", "-1 years +3 days"},
        // Gate: PostgreSQL >= 17. The binding pins 17 for this row and the one below.
        {"'infinity'::interval", "infinity"},
        // Gate: PostgreSQL >= 14.
        {"'Infinity'::numeric", "Infinity"},
        // Gate: PostgreSQL >= 12, where the server switched to shortest-round-trip. Below it — or at
        // extra_float_digits < 1 — it emits DBL_DIG+3 digits and this renders 0.100000001. The float8
        // half of this row is the 0.1+0.2 case in Tier A above; only float4 needs its own, because
        // that Tier A pair tests the fixed/scientific cutoff rather than the digit count.
        {"0.1::float4", "0.1"},
        // Use the DataRow length, never the declared 64-byte width, or NUL padding appears.
        {"'nm'::name", "nm"},
        // Gate: a server built with libxml.
        {"'<a>1</a>'::xml", "<a>1</a>"},
        // One byte, not char(1). Easy to confuse with bpchar, which pads.
        {"'x'::\"char\"", "x"},
    };

    /**
     * Tier C — no implementation renders these. Every one has a perfectly good server rendering and
     * arrives as a structured binary datum a naive driver turns into mojibake.
     */
    private static final String[][] TIER_C = {
        {"'{1,2}'::int4[]", "int4[] — arrays are the largest real gap, an ordinary application type"},
        {"'{a,b}'::text[]", "text[] — array_out quoting is a sub-algorithm of its own"},
        {"'{1.5}'::numeric[]", "numeric[]"},
        {"'[1,5)'::int4range", "int4range — shares the user OID range with enum"},
        {"'{[1,5)}'::int4multirange", "int4multirange"},
        {"'192.168.0.1/24'::inet", "inet"},
        {"'192.168.0.0/24'::cidr", "cidr"},
        {"'08:00:2b:01:02:03'::macaddr", "macaddr"},
        {"B'1011'", "bit — pgjdbc reports Types.BIT for this AND for bool, so only the name separates them"},
        {"B'1011'::varbit", "varbit"},
        {"'a b'::tsvector", "tsvector"},
        {"'0/16B3748'::pg_lsn", "pg_lsn"},
    };

    @Nested
    @DisplayName("Tier A — rendering")
    class TierARendering {

        @Test
        @DisplayName("getString renders every Tier A type exactly as the server does")
        void rendersEveryTierARow() {
            withPinnedConnection(conn -> {
                SoftAssertions softly = new SoftAssertions();
                for (String[] row : TIER_A) {
                    softly.assertThat(renderOne(conn, row[0]))
                            .as("getString of %s", row[0])
                            .isEqualTo(row[1]);
                }
                softly.assertAll();
            });
        }
    }

    // =========================================================================
    // Tier C — refusal, and the direction that keeps it honest
    // =========================================================================

    @Nested
    @DisplayName("Tier B — gated rendering")
    class TierBRendering {

        @Test
        @DisplayName("getString renders every Tier B type, each under its stated gate")
        void rendersEveryTierBRow() {
            withPinnedConnection(conn -> {
                SoftAssertions softly = new SoftAssertions();
                for (String[] row : TIER_B) {
                    softly.assertThat(renderOne(conn, row[0]))
                            .as("getString of %s", row[0])
                            .isEqualTo(row[1]);
                }
                softly.assertAll();
            });
        }

        /**
         * The gate {@link #pinSession} cannot supply: a zone that actually changes offset.
         *
         * <p>One instant proves nothing here. An implementation that applies the zone's <em>raw</em>
         * offset — ignoring daylight saving — renders the January instant correctly and the July one
         * an hour early, so a single-instant test passes against exactly the bug this row exists to
         * catch. Both, or neither.
         */
        @Test
        @DisplayName("timestamptz follows the session zone across a DST boundary, not its raw offset")
        void timestamptzHonoursDaylightSaving() {
            withPinnedConnection(conn -> {
                conn.executeUpdate("SET TimeZone TO 'Europe/Warsaw'");
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(renderOne(conn, "'2000-01-01 00:00:00+00'::timestamptz"))
                        .as("January — Warsaw is UTC+1")
                        .isEqualTo("2000-01-01 01:00:00+01");
                softly.assertThat(renderOne(conn, "'2000-07-01 00:00:00+00'::timestamptz"))
                        .as("July — Warsaw is UTC+2, and a raw-offset implementation says 01:00+01")
                        .isEqualTo("2000-07-01 02:00:00+02");
                softly.assertAll();
            });
        }
    }

    @Nested
    @DisplayName("Tier C — refusal")
    class TierCRefusal {

        @Test
        @DisplayName("an unimplemented type fails rather than returning a value")
        void refusesEveryTierCRow() {
            withPinnedConnection(conn -> {
                SoftAssertions softly = new SoftAssertions();
                for (String[] row : TIER_C) {
                    softly.assertThatThrownBy(() -> renderOne(conn, row[0]))
                            .as("getString of %s must refuse (%s)", row[0], row[1])
                            .isInstanceOf(PersistenceProviderException.class)
                            .extracting(refusalCode())
                            .isEqualTo(KernelErrorCodes.EX_PERS_5008);
                }
                softly.assertAll();
            });
        }

        @Test
        @DisplayName("a native enum refuses too — a text passthrough is accidentally correct")
        void refusesNativeEnum() {
            withPinnedConnection(conn -> {
                conn.executeUpdate("DROP TYPE IF EXISTS tck_mood CASCADE");
                conn.executeUpdate("CREATE TYPE tck_mood AS ENUM ('happy','sad')");
                // enum_send is a text passthrough, so wrapping the bytes in UTF-8 returns "happy"
                // and looks right. A driver that generalises from that to an OID-range heuristic
                // then corrupts ranges and composites, which share the user range and are binary.
                assertThatThrownBy(() -> renderOne(conn, "'happy'::tck_mood"))
                        .as("a native enum must refuse despite rendering plausibly")
                        .isInstanceOf(PersistenceProviderException.class)
                        .extracting(refusalCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5008);
                conn.executeUpdate("DROP TYPE IF EXISTS tck_mood CASCADE");
            });
        }

        @Test
        @DisplayName("a SQL NULL in an unsupported column refuses rather than returning null")
        void refusalIsAPropertyOfTheColumnNotTheRow() {
            withPinnedConnection(conn ->
                    assertThatThrownBy(() -> renderOne(conn, "NULL::tsvector"))
                            .as("null would report \"no value here\" when the truth is "
                                    + "\"this column cannot be rendered\"")
                            .isInstanceOf(PersistenceProviderException.class)
                            .extracting(refusalCode())
                            .isEqualTo(KernelErrorCodes.EX_PERS_5008));
        }

        /**
         * The direction that makes the refusal group non-vacuous: a driver refusing <em>every</em>
         * column satisfies all of the above. Tier A is asserted to render in its own group, and
         * this repeats one row here so the two groups cannot pass in each other's absence.
         */
        @Test
        @DisplayName("a supported type in the same session does not refuse")
        void supportedTypeStillRenders() {
            withPinnedConnection(conn ->
                    assertThat(renderOne(conn, "true::bool"))
                            .as("refusal must discriminate, not blanket")
                            .isEqualTo("t"));
        }
    }

    // =========================================================================
    // Domains — the row that pins why the rule is per declared type
    // =========================================================================

    @Nested
    @DisplayName("Domains")
    class Domains {

        /**
         * Measured: PostgreSQL reports the <em>base</em> type in the row description, so a domain
         * over {@code int4} arrives as {@code int4}. Worth pinning precisely because the opposite
         * belief is easy to hold, and it is what would justify the OID-range heuristic that
         * silently corrupts ranges and composites.
         */
        @Test
        @DisplayName("a domain renders as its base type, needing no handling of its own")
        void domainRendersAsBaseType() {
            withPinnedConnection(conn -> {
                conn.executeUpdate("DROP DOMAIN IF EXISTS tck_posint CASCADE");
                conn.executeUpdate("CREATE DOMAIN tck_posint AS int4 CHECK (VALUE > 0)");
                assertThat(renderOne(conn, "5::tck_posint")).isEqualTo("5");
                conn.executeUpdate("DROP DOMAIN IF EXISTS tck_posint CASCADE");
            });
        }
    }

    // =========================================================================
    // Mismatched typed accessors (ADR-080 §3)
    // =========================================================================

    @Nested
    @DisplayName("Mismatched typed accessor")
    class MismatchedTypedAccessor {

        @Test
        @DisplayName("widens where widening is lossless")
        void widensWhereLossless() {
            withPinnedConnection(conn -> {
                try (QueryResult result = conn.executeQuery("SELECT 12345::int2")) {
                    assertThat(result.next()).isTrue();
                    // The failure this forbids is reading four bytes at a two-byte column's offset
                    // and returning 809500672 — a number, plausible, and wrong.
                    assertThat(result.row().getInt(0)).isEqualTo(12345);
                }
            });
        }

        @Test
        @DisplayName("throws where the value does not fit, rather than truncating")
        void throwsWhereLossy() {
            withPinnedConnection(conn -> {
                try (QueryResult result = conn.executeQuery("SELECT 9223372036854775807::int8")) {
                    assertThat(result.next()).isTrue();
                    RowCursor row = result.row();
                    assertThatThrownBy(() -> row.getInt(0))
                            .as("a value outside int range must fail, not wrap")
                            .isInstanceOf(RuntimeException.class);
                }
            });
        }

        @Test
        @DisplayName("the matching typed accessor agrees with getString")
        void matchingAccessorAgrees() {
            withPinnedConnection(conn -> {
                try (QueryResult result = conn.executeQuery("SELECT 808464432::int4, true::bool")) {
                    assertThat(result.next()).isTrue();
                    RowCursor row = result.row();
                    assertThat(row.getInt(0)).isEqualTo(808464432);
                    assertThat(row.getString(0)).isEqualTo("808464432");
                    assertThat(row.getBoolean(1)).isTrue();
                    assertThat(row.getString(1)).isEqualTo("t");
                }
            });
        }
    }

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    /**
     * The refusal must carry its own error code, not merely fail.
     *
     * <p>Asserting {@code RuntimeException} would pass on any SQL error the driver happened to
     * raise — including one raised for a completely different reason — so it would report the
     * contract as met by an accident.
     */
    private static java.util.function.Function<Throwable, String> refusalCode() {
        return thrown -> ((PersistenceProviderException) thrown).errorCode();
    }

    private String renderOne(PersistenceConnection conn, String expression) {
        try (QueryResult result = conn.executeQuery("SELECT " + expression)) {
            assertThat(result.next()).as("query for %s must produce a row", expression).isTrue();
            return result.row().getString(0);
        }
    }

    private void withPinnedConnection(java.util.function.Consumer<PersistenceConnection> body) {
        try (PersistenceConnection conn = engine.openConnection()) {
            pinSession(conn);
            body.accept(conn);
        }
    }
}
