/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractConnectionInterceptorInitTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

@DisplayName("Community: ConnectionInterceptor init TCK")
class CommunityConnectionInterceptorInitTckTest extends AbstractConnectionInterceptorInitTck {

    @Override
    protected PersistenceEngine createEngine() {
        return new CommunityPersistenceProvider().createEngine(testConfig());
    }

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_conn_interceptor_init_tck_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                8,
                2,
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
