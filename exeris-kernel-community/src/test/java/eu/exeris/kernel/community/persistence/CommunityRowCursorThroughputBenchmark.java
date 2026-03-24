/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.tck.perf.AbstractRowCursorThroughputBenchmark;

/**
 * Community row-cursor throughput benchmark binding.
 */
public class CommunityRowCursorThroughputBenchmark extends AbstractRowCursorThroughputBenchmark {

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS benchmark_data (id BIGINT PRIMARY KEY, value VARCHAR(64), ts BIGINT)";
    private static final String DELETE_ROWS_SQL = "DELETE FROM benchmark_data";
    private static final String INSERT_ROW_PREFIX = "INSERT INTO benchmark_data (id, value, ts) VALUES (";

    @Override
    protected PersistenceProvider getProvider() {
        return new CommunityPersistenceProvider();
    }

    @Override
    protected void prepareBenchmarkData(PersistenceEngine engine) {
        try (PersistenceConnection setupConnection = engine.openConnection()) {
            setupConnection.beginTransaction();
            setupConnection.executeUpdate(CREATE_TABLE_SQL);
            setupConnection.executeUpdate(DELETE_ROWS_SQL);
            for (int i = 0; i < 1000; i++) {
                setupConnection.executeUpdate(INSERT_ROW_PREFIX + i + ", 'value-" + i + "', " + i + ")");
            }
            setupConnection.commit();
        }
    }
}
