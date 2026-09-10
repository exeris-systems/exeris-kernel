/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractPersistenceEngineTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

/**
 * Community concrete TCK for {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}.
 *
 * @since 0.5.0
 */
@DisplayName("Community: PersistenceEngine TCK")
class CommunityPersistenceEngineTckTest extends AbstractPersistenceEngineTck {

    @Override
    protected PersistenceEngine createEngine() {
        return new CommunityPersistenceProvider().createEngine(testConfig());
    }

    @Override
    protected PersistenceEngine createEngineWithDedicatedConfig(String dedicatedKey) {
        PersistenceConfig dedicatedCfg = new PersistenceConfig(
                "jdbc:h2:mem:engine_tck_ded_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of());
        PersistenceConfig mainCfg = new PersistenceConfig(
                "jdbc:h2:mem:engine_tck_main_ded_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of(),
                Map.of(dedicatedKey, dedicatedCfg));
        return new CommunityPersistenceProvider().createEngine(mainCfg);
    }

    @Override
    protected PersistenceEngine createEngineWithDedicatedRlsEnabledConfig(String dedicatedKey) {
        PersistenceConfig dedicatedCfg = new PersistenceConfig(
                "jdbc:h2:mem:engine_tck_ded_rls_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of());
        // rlsEnabled=true — DEDICATED strategy must not require interceptor
        PersistenceConfig mainCfg = new PersistenceConfig(
                "jdbc:h2:mem:engine_tck_main_rls_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L, true, false, false, 0, Map.of(),
                Map.of(dedicatedKey, dedicatedCfg));
        return new CommunityPersistenceProvider().createEngine(mainCfg);
    }

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_persistence_engine_tck_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
