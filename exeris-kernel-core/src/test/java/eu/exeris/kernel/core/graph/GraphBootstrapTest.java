/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.graph.GraphBootstrapException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link GraphBootstrap} — priority selection and no-provider guard.
 *
 * <h2>Note on ServiceLoader</h2>
 * <p>Because {@code ServiceLoader} reads the classpath, these tests verify the
 * exception contract only (no provider on the test classpath). Provider selection
 * with multiple candidates is tested in TCK integration tests that supply actual
 * implementations.
 *
 * @since 0.5.0
 */
@DisplayName("L1: GraphBootstrap — priority selection, no-provider guard")
class GraphBootstrapTest {

    // =========================================================================
    // Stub engine / provider
    // =========================================================================

    private static GraphEngine stubEngine(String name) {
        return new GraphEngine() {
            @Override public GraphSession openSession() { return null; }
            @Override public GraphDialect dialect()     { return null; }
            @Override public void registerNodes(List<GraphNodeDescriptor> n) {}
            @Override public void registerEdges(List<GraphEdgeDescriptor> e) {}
            @Override public List<GraphNodeDescriptor> registeredNodes() { return List.of(); }
            @Override public List<GraphEdgeDescriptor> registeredEdges() { return List.of(); }
            @Override public String engineName()  { return name; }
            @Override public boolean isRunning()  { return true; }
            @Override public void close()         {}
        };
    }

    private static GraphProvider stubProvider(int prio, String id) {
        return new GraphProvider() {
            @Override public GraphEngine createEngine(GraphConfig c) { return stubEngine(id); }
            @Override public String providerId()   { return id; }
            @Override public String providerName() { return id + "-provider"; }
            @Override public int priority()        { return prio; }
        };
    }

    // =========================================================================
    // Priority selection (direct provider API — does not use ServiceLoader)
    // =========================================================================

    @Test
    @DisplayName("higher-priority provider wins in direct API comparison")
    void higherPriorityWins() {
        GraphProvider community  = stubProvider(0,   "community");
        GraphProvider enterprise = stubProvider(100, "enterprise");
        GraphProvider winner = java.util.stream.Stream.of(community, enterprise)
                .max(java.util.Comparator.comparingInt(GraphProvider::priority))
                .orElseThrow();
        assertThat(winner.providerId()).isEqualTo("enterprise");
    }

    @Test
    @DisplayName("lower-priority provider loses in direct API comparison")
    void lowerPriorityLoses() {
        GraphProvider community = stubProvider(0, "community");
        GraphProvider winner = java.util.stream.Stream.of(community)
                .max(java.util.Comparator.comparingInt(GraphProvider::priority))
                .orElseThrow();
        assertThat(winner.providerId()).isEqualTo("community");
    }

    // =========================================================================
    // GraphBootstrapException error code
    // =========================================================================

    @Test
    @DisplayName("GraphBootstrapException carries EX-GRPH-5001 error code")
    void exceptionCarriesCorrectErrorCode() {
        GraphBootstrapException ex = new GraphBootstrapException("TestProvider", "missing");
        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5001);
    }

    @Test
    @DisplayName("GraphBootstrapException rawArgs[0] is providerName")
    void rawArgsProviderName() {
        GraphBootstrapException ex = new GraphBootstrapException("MyProvider", "bad config");
        assertThat(ex.rawArgs()[0]).isEqualTo("MyProvider");
    }

    @Test
    @DisplayName("GraphBootstrapException rawArgs[1] is reason string")
    void rawArgsReason() {
        GraphBootstrapException ex = new GraphBootstrapException("MyProvider", "bad config");
        assertThat(ex.rawArgs()[1]).isEqualTo("bad config");
    }
}

