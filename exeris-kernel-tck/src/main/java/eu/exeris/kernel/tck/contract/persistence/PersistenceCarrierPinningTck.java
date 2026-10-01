/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK: Carrier pinning verifier for the Persistence query hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code openConnection() → executeQuery() → next() → getInt(0) → close()}.
 * All I/O must be non-blocking and VT-safe — zero carrier pinning.
 *
 * @since 0.5
 * @see AbstractSubsystemCarrierPinningTck
 * @see PersistenceZeroAllocTck
 */
@DisplayName("Persistence carrier pinning TCK")
public abstract class PersistenceCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine}.
     *
     * @return an engine whose connections can run {@link #hotPathQuery()}
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Returns the query executed on every iteration of the hot path under test.
     *
     * @return a query returning at least one row with an {@code int} at column 0
     */
    protected abstract String hotPathQuery();

    private PersistenceEngine engine;
    private String sql;

    /**
     * Creates the contract; subclasses supply the engine via {@link #createEngine()} and the
     * hot-path query via {@link #hotPathQuery()}.
     */
    public PersistenceCarrierPinningTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override
    protected String subsystemName() {
        return "Persistence";
    }

    @Override
    protected String hotPathDescription() {
        return "openConnection() → executeQuery() → next() → getInt(0) → close()";
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
                rs.row().getInt(0);
            }
        }
    }

    @Override
    protected void tearDownSubsystem() {
        engine.close();
    }
}
