/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link RowCursor} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Flyweight pattern: same {@link RowCursor} instance across rows</li>
 *   <li>Primitive accessors ({@code getInt}, {@code getLong}) return correct values</li>
 *   <li>{@code close()} releases native memory via {@code MemoryAllocator}</li>
 *   <li>{@code isNull()} correctly identifies SQL NULL values</li>
 * </ul>
 *
 * <h2>Memory Rule</h2>
 * <p>RowCursor MUST release backing native memory from {@code MemoryAllocator}
 * after {@code QueryResult.close()}. With {@code LeakDetectionMode.PARANOID},
 * any unreleased segment triggers a test failure.
 *
 * @since 0.5.0
 */
public abstract class AbstractRowCursorTck {

    /**
     * Creates a bootstrapped {@link PersistenceEngine} with a test table.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Returns the SQL query that produces at least one row with known column values.
     * The query must return columns: (int, bigint, text) in that order.
     * Example: {@code "SELECT 42, 9999999999, 'hello'"}.
     */
    protected abstract String testQuery();

    /**
     * Expected int value in column 0 of the test query result.
     */
    protected abstract int expectedInt();

    /**
     * Expected long value in column 1 of the test query result.
     */
    protected abstract long expectedLong();

    /**
     * Expected String value in column 2 of the test query result.
     */
    protected abstract String expectedString();

    /**
     * Returns a query producing one row whose every column is SQL NULL, in the same
     * {@code (int, bigint, text)} column order as {@link #testQuery()}.
     *
     * <p>The default is portable SQL. A binding whose engine types a bare {@code CAST(NULL AS ...)}
     * differently overrides it — but the shape must hold: three columns, all NULL, in that order.
     */
    protected String nullRowQuery() {
        return "SELECT CAST(NULL AS INTEGER), CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR)";
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
    // Flyweight contract
    // =========================================================================

    @Nested
    @DisplayName("Flyweight contract")
    class FlyweightContract {

        @Test
        @DisplayName("row() returns the same RowCursor instance across next() calls")
        void sameInstanceAcrossRows() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                RowCursor first = null;
                while (result.next()) {
                    RowCursor current = result.row();
                    if (first == null) {
                        first = current;
                    } else {
                        assertThat(current)
                                .as("RowCursor must be a flyweight — same instance reused")
                                .isSameAs(first);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Primitive access
    // =========================================================================

    @Nested
    @DisplayName("Primitive access contract")
    class PrimitiveAccess {

        @Test
        @DisplayName("getInt() returns correct value")
        void getIntCorrect() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().getInt(0)).isEqualTo(expectedInt());
            }
        }

        @Test
        @DisplayName("getLong() returns correct value")
        void getLongCorrect() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().getLong(1)).isEqualTo(expectedLong());
            }
        }

        @Test
        @DisplayName("getString() returns correct value (allocating path)")
        void getStringCorrect() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().getString(2)).isEqualTo(expectedString());
            }
        }

        @Test
        @DisplayName("columnCount() matches query columns")
        void columnCountMatches() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().columnCount()).isGreaterThanOrEqualTo(3);
            }
        }
    }

    // =========================================================================
    // Resource release
    // =========================================================================

    @Nested
    @DisplayName("Resource release contract")
    class ResourceRelease {

        @Test
        @DisplayName("QueryResult.close() releases resources — no leak")
        void closeReleasesResources() {
            PersistenceConnection conn = engine.openConnection();
            QueryResult result = conn.executeQuery(testQuery());
            while (result.next()) {
                result.row().getInt(0); // consume
            }
            assertThatCode(result::close).doesNotThrowAnyException();
            assertThatCode(conn::close).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // SQL NULL contract (ADR-080 ruling 1)
    // =========================================================================

    @Nested
    @DisplayName("SQL NULL contract")
    class SqlNullContract {

        @Test
        @DisplayName("isNull() is true for every column of an all-NULL row")
        void isNullTrueForEveryColumn() {
            withNullRow(row -> {
                for (int column = 0; column < 3; column++) {
                    assertThat(row.isNull(column))
                            .as("isNull(%d) on an all-NULL row", column)
                            .isTrue();
                }
            });
        }

        @Test
        @DisplayName("reference accessors return null rather than throwing")
        void referenceAccessorsReturnNull() {
            withNullRow(row -> {
                assertThat(row.getString(2)).as("getString on SQL NULL").isNull();
                assertThat(row.getBytes(2)).as("getBytes on SQL NULL").isNull();
                assertThat(row.getUuid(2)).as("getUuid on SQL NULL").isNull();
                assertThat(row.getInstant(1)).as("getInstant on SQL NULL").isNull();
            });
        }

        @Test
        @DisplayName("getLength() reports -1, which is the length no value has")
        void getLengthReportsMinusOne() {
            withNullRow(row -> assertThat(row.getLength(2)).isEqualTo(-1));
        }

        @Test
        @DisplayName("getSegment() throws — a read-only view of nothing is not a segment")
        void getSegmentThrowsOnNull() {
            withNullRow(row -> assertThatThrownBy(() -> row.getSegment(2))
                    .isInstanceOf(NullPointerException.class));
        }

        @Test
        @DisplayName("primitive accessors throw — they have no null to return")
        void primitiveAccessorsThrowOnNull() {
            withNullRow(row -> {
                assertThatThrownBy(() -> row.getInt(0)).as("getInt").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> row.getLong(1)).as("getLong").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> row.getShort(0)).as("getShort").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> row.getFloat(0)).as("getFloat").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> row.getDouble(0)).as("getDouble").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> row.getBoolean(0)).as("getBoolean").isInstanceOf(NullPointerException.class);
            });
        }

        /**
         * The direction that makes the group non-vacuous: a cursor answering "NULL" to everything
         * would satisfy every assertion above. These prove the same accessors return the value on a
         * row that has one, so the NULL behaviour is a response to the datum and not a constant.
         *
         * <p>Every accessor asserted to throw or to return a sentinel on NULL needs its counterpart
         * here, or that accessor's NULL case is satisfied by an implementation that always throws.
         * {@code getSegment} and {@code getBytes} are the two that reach for the raw bytes, and both
         * are reachable on the text column.
         *
         * <p>{@code getUuid} and {@code getInstant} are deliberately absent: their behaviour on a
         * text column is the type-domain question ADR-080 §3 rules on, asserted by the type-set half
         * of this TCK against a fixture where the column types are known. Asserting them here would
         * pin driver-specific coercion as though it were the contract.
         */
        @Test
        @DisplayName("the same accessors return values on a non-NULL row")
        void accessorsReturnValuesWhenNotNull() {
            try (PersistenceConnection conn = engine.openConnection();
                 QueryResult result = conn.executeQuery(testQuery())) {
                assertThat(result.next()).isTrue();
                RowCursor row = result.row();
                assertThat(row.isNull(0)).as("isNull on a populated column").isFalse();
                assertThat(row.getString(2)).isEqualTo(expectedString());
                assertThat(row.getBytes(2)).as("getBytes on a populated column").isNotNull();
                assertThat(row.getLength(2)).as("getLength on a populated column").isNotEqualTo(-1);
                assertThat(row.getSegment(2).byteSize())
                        .as("getSegment must span exactly the bytes getLength reports")
                        .isEqualTo(row.getLength(2));
                assertThat(row.getInt(0)).isEqualTo(expectedInt());
            }
        }
    }

    // =========================================================================
    // Column-index contract (ADR-080 ruling 1)
    // =========================================================================

    @Nested
    @DisplayName("Column-index contract")
    class ColumnIndexContract {

        @Test
        @DisplayName("every accessor rejects a negative index")
        void everyAccessorRejectsNegativeIndex() {
            withFirstRow(row -> assertEveryAccessorRejects(row, -1));
        }

        @Test
        @DisplayName("every accessor rejects columnCount(), the first index past the end")
        void everyAccessorRejectsIndexPastEnd() {
            withFirstRow(row -> assertEveryAccessorRejects(row, row.columnCount()));
        }

        /**
         * Uniform across all thirteen accessors on purpose. Only {@code getInt} and {@code getSegment}
         * declared any exception before ADR-080; the other eleven behaved this way without saying so,
         * which is the difference between a contract and a coincidence.
         */
        private void assertEveryAccessorRejects(RowCursor row, int column) {
            assertOutOfBounds("getInt", () -> row.getInt(column));
            assertOutOfBounds("getLong", () -> row.getLong(column));
            assertOutOfBounds("getShort", () -> row.getShort(column));
            assertOutOfBounds("getFloat", () -> row.getFloat(column));
            assertOutOfBounds("getDouble", () -> row.getDouble(column));
            assertOutOfBounds("getBoolean", () -> row.getBoolean(column));
            assertOutOfBounds("isNull", () -> row.isNull(column));
            assertOutOfBounds("getSegment", () -> row.getSegment(column));
            assertOutOfBounds("getLength", () -> row.getLength(column));
            assertOutOfBounds("getString", () -> row.getString(column));
            assertOutOfBounds("getBytes", () -> row.getBytes(column));
            assertOutOfBounds("getUuid", () -> row.getUuid(column));
            assertOutOfBounds("getInstant", () -> row.getInstant(column));
        }

        private void assertOutOfBounds(String accessor, ThrowableAssert.ThrowingCallable call) {
            assertThatThrownBy(call)
                    .as("%s must reject an out-of-range column index", accessor)
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    private void withNullRow(Consumer<RowCursor> assertion) {
        try (PersistenceConnection conn = engine.openConnection();
             QueryResult result = conn.executeQuery(nullRowQuery())) {
            assertThat(result.next()).as("the all-NULL query must produce a row").isTrue();
            assertion.accept(result.row());
        }
    }

    private void withFirstRow(Consumer<RowCursor> assertion) {
        try (PersistenceConnection conn = engine.openConnection();
             QueryResult result = conn.executeQuery(testQuery())) {
            assertThat(result.next()).isTrue();
            assertion.accept(result.row());
        }
    }
}
