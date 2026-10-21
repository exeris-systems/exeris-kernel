/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.community.persistence.RlsConnectionInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSessionLiveContractTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.function.Supplier;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: GraphSession contract — live PostgreSQL (SQL/PGQ backend)")
class CommunityGraphSessionPostgresContractTckIT extends AbstractGraphSessionLiveContractTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine persistence;
    private static CommunityGraphEngine graph;

    @BeforeAll
    static void startEngines() {
        persistence = new CommunityPersistenceProvider().createEngine(new PersistenceConfig(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of()));
        persistence.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
        graph = new CommunityGraphEngine(GraphConfig.create("postgresql"));
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistence).run(() -> {
            graph.registerNodes(nodeTypes());
            graph.registerEdges(edgeTypes());
        });
    }

    @AfterAll
    static void stopEngines() {
        if (graph != null) {
            graph.close();
        }
        if (persistence != null) {
            persistence.close();
        }
    }

    @Override
    protected GraphEngine engine() {
        return graph;
    }

    @Override
    protected <T> T callInScope(StorageContext ctx, Supplier<T> action) {
        return ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistence)
                .where(KernelProviders.STORAGE_CONTEXT, ctx)
                .call(action::get);
    }
}
