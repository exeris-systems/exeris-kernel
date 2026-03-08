/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see PersistenceZeroAllocTck
 */
@DisplayName("Persistence carrier pinning TCK")
public abstract class PersistenceCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    protected abstract PersistenceEngine createEngine();
    protected abstract String hotPathQuery();

    private PersistenceEngine engine;
    private String sql;

    @Override protected String subsystemName()      { return "Persistence"; }
    @Override protected String hotPathDescription() { return "openConnection() → executeQuery() → next() → getInt(0) → close()"; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        sql    = hotPathQuery();
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
