/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.graph.GraphBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

final class CommunityGraphSubsystem implements Subsystem {

    private GraphProvider graphProvider;
    private GraphEngine graphEngine;
    private boolean running;

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory", "persistence");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        GraphConfig config = buildGraphConfig(configProvider);
        GraphBootstrap.BootstrapResult bootstrap = GraphBootstrap.loadWithProvider(config);
        graphProvider = bootstrap.provider();
        graphEngine = bootstrap.engine();
    }

    @Override
    public void start() {
        running = graphEngine != null && graphEngine.isRunning();
    }

    @Override
    public void stop() {
        running = false;
        if (graphEngine != null) {
            graphEngine.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (graphProvider == null || graphEngine == null) {
            return Subsystem.super.providerBindings();
        }
        return carrier -> carrier
                .where(KernelProviders.GRAPH_PROVIDER, graphProvider)
                .where(KernelProviders.GRAPH_ENGINE, graphEngine);
    }

    private static GraphConfig buildGraphConfig(ConfigProvider configProvider) {
        String backendType = configProvider.getString("graph.backendType")
                .orElse("postgresql");
        String graphName = configProvider.getString("graph.graphName")
                .orElse("exeris_graph");
        boolean cachingEnabled = configProvider.getBoolean("graph.cachingEnabled")
                .orElse(false);
        boolean indexingEnabled = configProvider.getBoolean("graph.indexingEnabled")
                .orElse(true);
        boolean syncEnabled = configProvider.getBoolean("graph.syncEnabled")
                .orElse(true);
        boolean pathFinderEnabled = configProvider.getBoolean("graph.pathFinderEnabled")
                .orElse(true);
        Map<String, String> properties = new LinkedHashMap<>();
        configProvider.getString("graph.neo4j.uri")
            .ifPresent(value -> properties.put("neo4j.uri", value));
        configProvider.getString("graph.neo4j.user")
            .ifPresent(value -> properties.put("neo4j.user", value));
        configProvider.getString("graph.neo4j.password")
            .ifPresent(value -> properties.put("neo4j.password", value));
        configProvider.getString("graph.neo4j.database")
            .ifPresent(value -> properties.put("neo4j.database", value));

        return new GraphConfig(
                backendType,
                graphName,
                cachingEnabled,
                indexingEnabled,
                syncEnabled,
                pathFinderEnabled,
            Map.copyOf(properties)
        );
    }
}
