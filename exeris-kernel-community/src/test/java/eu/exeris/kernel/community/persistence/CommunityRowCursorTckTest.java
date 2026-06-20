/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractRowCursorTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

/**
 * Community concrete TCK for {@link eu.exeris.kernel.spi.persistence.RowCursor}, backed by
 * the H2 (PostgreSQL-mode) JDBC {@link CommunityPersistenceEngine}.
 *
 * <p>The contract query returns one row of {@code (int, bigint, text)}. H2 in PostgreSQL
 * compatibility mode types an integer literal as {@code INTEGER}, a literal wider than 32 bits
 * cast to {@code BIGINT}, and a quoted literal as {@code VARCHAR} — matching the column order
 * the TCK reads via {@code getInt(0) / getLong(1) / getString(2)}.
 *
 * @since 0.9.0
 */
@DisplayName("Community: RowCursor TCK")
class CommunityRowCursorTckTest extends AbstractRowCursorTck {

    private static final int    EXPECTED_INT    = 42;
    private static final long   EXPECTED_LONG   = 9_999_999_999L;
    private static final String EXPECTED_STRING = "hello";

    @Override
    protected PersistenceEngine createEngine() {
        return new CommunityPersistenceProvider().createEngine(testConfig());
    }

    @Override
    protected String testQuery() {
        return "SELECT " + EXPECTED_INT
                + ", CAST(" + EXPECTED_LONG + " AS BIGINT)"
                + ", CAST('" + EXPECTED_STRING + "' AS VARCHAR)";
    }

    @Override
    protected int expectedInt() {
        return EXPECTED_INT;
    }

    @Override
    protected long expectedLong() {
        return EXPECTED_LONG;
    }

    @Override
    protected String expectedString() {
        return EXPECTED_STRING;
    }

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_rowcursor_tck_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                16,
                4,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of()
        );
    }
}
