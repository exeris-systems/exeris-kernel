/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
}
