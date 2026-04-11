/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * TCK JMH Benchmark: Persistence Row Cursor Throughput — the "Zero-ORM" Test.
 *
 * <h2>What is measured</h2>
 * <p>The speed of iterating over large result sets using the flyweight
 * {@link RowCursor} — without instantiating a single Java entity object.
 * Where Hibernate would materialise hundreds of thousands of {@code UserEntity}
 * objects per second and evict them into the GC, the Exeris persistence SPI
 * reads {@code long} and {@code String} values directly from the off-heap
 * receive buffer.
 *
 * <h2>Why this matters</h2>
 * <p>Community implementations delegate to JDBC {@code ResultSet} — boxing and
 * per-row heap allocation may occur. Enterprise implementations back
 * {@link QueryResult} with a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}
 * over the PostgreSQL wire protocol receive ring. {@link QueryResult#next()}
 * is O(1) and allocates zero heap objects. The gap between the two tiers in
 * this benchmark is the quantified cost of the ORM layer.
 *
 * <h2>Flyweight contract</h2>
 * <p>The {@link RowCursor} returned by {@link QueryResult#row()} is a <em>reused</em>
 * instance — it is repositioned over each successive row's byte offset in the
 * receive buffer. Callers MUST NOT retain references across {@link QueryResult#next()}
 * calls. Primitive reads ({@code getLong}, {@code getInt}) are O(1) off-heap reads
 * in the Enterprise tier.
 *
 * <h2>Allocating methods</h2>
 * <p>{@link RowCursor#getString(int)} is marked as allocating in the SPI Javadoc
 * and is included here deliberately — it represents the minimum unavoidable allocation
 * cost for a {@code VARCHAR} column that cannot be expressed as a primitive. The
 * blackhole prevents the JIT from dead-code-eliminating the string construction.
 *
 * <h2>Implementing this benchmark</h2>
 * <pre>{@code
 * public class MyCommunityRowCursorBenchmark
 *         extends AbstractRowCursorThroughputBenchmark {
 *
 *     \@Override
 *     protected PersistenceProvider getProvider() {
 *         return new CommunityJdbcProvider();
 *     }
 *
 *     \@Override
 *     protected void prepareBenchmarkData(PersistenceEngine engine) {
 *         // Insert 1 000 rows into an in-memory H2 table
 *         try (PersistenceConnection conn = engine.openConnection()) {
 *             conn.executeUpdate("CREATE TABLE benchmark_data (id BIGINT, value_text VARCHAR, ts BIGINT)");
 *             for (int i = 0; i < 1_000; i++) {
 *                 conn.executeUpdate("INSERT INTO benchmark_data VALUES (" + i + ", 'v" + i + "', " + i + ")");
 *             }
 *             conn.commit();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractExerisBenchmark
 */
public abstract class AbstractRowCursorThroughputBenchmark extends AbstractExerisBenchmark {

    /**
     * Pre-warmed query string — 1 000 rows is sufficient for steady-state JIT
     * without overwhelming the measurement window with I/O wait time.
     */
    private static final String BENCHMARK_QUERY =
            "SELECT id, value_text, ts FROM benchmark_data";

    /**
     * Default JDBC URL: H2 in-memory, PostgreSQL compatibility mode. Override
     * via system property {@code benchmark.persistence.url} for real backends.
     */
    private static final String DEFAULT_BENCHMARK_URL =
            "jdbc:h2:mem:benchmark;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    /** The engine under test — initialised once per trial. */
    protected PersistenceEngine engine;

    /**
     * Open connection used for the read-only transaction in the measurement window.
     * Held open for the duration of the trial to avoid connection-acquisition
     * overhead contaminating the iteration timings.
     */
    protected PersistenceConnection connection;

    /**
     * Implementations must return the {@link PersistenceProvider} under test.
     *
     * @return non-null provider (Community or Enterprise)
     */
    protected abstract PersistenceProvider getProvider();

    /**
     * Hook invoked after the engine has started but before the benchmark connection
     * is opened. Implementations must insert the mock rows that the benchmark query
     * will iterate over.
     *
     * <p>Example: for an in-memory H2 or SQLite backend, create the {@code benchmark_data}
     * table and populate it with at least 1 000 rows.
     *
     * @param engine the started engine whose connection pool is available
     */
    protected abstract void prepareBenchmarkData(PersistenceEngine engine);

    /**
     * Trial-level setup: creates the engine, inserts benchmark data, and opens the
     * long-lived read-only transaction connection used across all measurement iterations.
     *
     * <p>{@link PersistenceEngine} is ready for use immediately after
     * {@link PersistenceProvider#createEngine(PersistenceConfig)} returns — there is
     * no explicit {@code start()} phase in the SPI.
     *
     * <p>The connection is opened with default isolation
     * ({@link eu.exeris.kernel.spi.persistence.TransactionIsolation#READ_COMMITTED})
     * to reflect real analytics workloads and allow the backend to skip write-conflict
     * detection overhead.
     */
    @Setup(Level.Trial)
    public void setupPersistence() {
        String rawUrl = System.getProperty("benchmark.persistence.url", "");
        String normalized = rawUrl.strip();
        String url = (!normalized.isBlank()
                && normalized.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:"))
                ? normalized
                : DEFAULT_BENCHMARK_URL;
        String benchUser = System.getProperty("benchmark.persistence.user",
                url.equals(DEFAULT_BENCHMARK_URL) ? "sa" : "bench");
        String benchPassword = System.getProperty("benchmark.persistence.password",
                url.equals(DEFAULT_BENCHMARK_URL) ? "" : "bench");
        this.engine = getProvider().createEngine(
                PersistenceConfig.defaults(url, benchUser, benchPassword)
        );

        // Insert mock rows — implementation-specific (in-memory DB or pre-warmed buffer).
        prepareBenchmarkData(engine);

        // Open the hot connection and start a read-only transaction.
        // The connection is reused across all measurement iterations to isolate
        // query-execution overhead from pool-acquisition overhead.
        this.connection = engine.openConnection();
        this.connection.beginTransaction(eu.exeris.kernel.spi.persistence.TransactionIsolation.READ_COMMITTED, true);
    }

    /**
     * Trial-level teardown: rolls back the open transaction and closes the engine,
     * ensuring that any transient state from the benchmark does not persist.
     */
    @TearDown(Level.Trial)
    public void tearDownPersistence() {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Hot-path benchmark — iterates over 1 000 rows using the flyweight {@link RowCursor}
     * without allocating entity objects on the heap.
     *
     * <p>Reads two primitive {@code long} columns and one {@code String} column per row.
     * The {@code String} allocation is unavoidable for {@code VARCHAR} data; all other
     * reads are O(1) off-heap in the Enterprise tier. The blackhole prevents the JIT
     * from eliminating the primitive reads as dead code.
     *
     * @param bh JMH blackhole — prevents dead-code elimination of cursor reads
     */
    @Benchmark
    public void rowCursorIteration(Blackhole bh) {
        // QueryResult is RAII: close() releases the LoanedBuffer back to the pool
        // (Enterprise) or closes the JDBC ResultSet (Community).
        try (QueryResult result = connection.executeQuery(BENCHMARK_QUERY)) {
            while (result.next()) {
                // Flyweight cursor — the SAME RowCursor instance is repositioned on each row.
                // Do NOT retain a reference to 'cursor' across next() calls.
                RowCursor cursor = result.row();

                // Column 0: id — primitive long, O(1) off-heap read (Enterprise)
                long id = cursor.getLong(0);

                // Column 1: value — String, allocates (unavoidable for VARCHAR)
                String value = cursor.getString(1);

                // Column 2: timestamp — primitive long, O(1) off-heap read (Enterprise)
                long ts = cursor.getLong(2);

                // Consume primitives so the JIT cannot eliminate the reads as dead code.
                bh.consume(id);
                bh.consume(value);
                bh.consume(ts);
            }
        }
    }
}
