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
import eu.exeris.kernel.tck.contract.persistence.PersistenceCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

/**
 * Community concrete carrier-pinning TCK binding for persistence hot path.
 *
 * @since 0.5.0
 */
@DisplayName("Community: Persistence carrier pinning TCK")
class CommunityPersistenceCarrierPinningTckTest extends PersistenceCarrierPinningTck {

    @Override
    protected PersistenceEngine createEngine() {
        return new CommunityPersistenceProvider().createEngine(testConfig());
    }

    @Override
    protected String hotPathQuery() {
        return "SELECT 1";
    }

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_persistence_pinning_tck_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
