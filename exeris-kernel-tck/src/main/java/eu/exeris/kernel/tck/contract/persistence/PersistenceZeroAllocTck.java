/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

/**
 * TCK: JFR Zero-Allocation monitor for the Persistence query hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code openConnection() → executeQuery() → while(next()) → row().getInt(0) → close()}.
 * Enterprise tier reads directly from off-heap {@code LoanedBuffer}.
 * Community tier wraps JDBC ResultSet (bounded allocations).
 *
 * @since 0.5.0
 */
public abstract class PersistenceZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Creates a bootstrapped {@link PersistenceEngine} with connection pool warmed.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Returns the SQL query to run on each hot-path iteration.
     * Must return at least one row with an int column at index 0.
     * Example: {@code "SELECT 1"}.
     */
    protected abstract String hotPathQuery();

    private PersistenceEngine engine;
    private String sql;

    @Override
    protected String subsystemName() {
        return "Persistence";
    }

    @Override
    protected String hotPathDescription() {
        return "openConnection() → executeQuery() → next() → getInt(0) → close()";
    }

    @Override
    protected int warmupIterations() {
        return 100;
    }

    @Override
    protected int hotPathIterations() {
        return 1_000;
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 8;
    }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        sql = hotPathQuery();
    }

    @Override
    protected void runSingleIteration() {
        try (PersistenceConnection conn = engine.openConnection();
             QueryResult rs = conn.executeQuery(sql)) {
            while (rs.next()) {
                rs.row().getInt(0); // consume — zero-alloc on Enterprise
            }
        }
    }

    @Override
    protected void tearDownSubsystem() {
        engine.close();
    }
}
