/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link AlgoOrchestrator} for every algorithm invocation.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted for both found and not-found results. Duration from {@link Event#begin()}
 * to {@link Event#commit()} captures the full algorithm wall-clock time including
 * any path-finder delegation overhead.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.graph.AlgoOrchestrator")
@Label("Graph Algorithm Invocation")
@Category({"Exeris Kernel", "Graph"})
@Description("Emitted for each AlgoOrchestrator algorithm invocation (Dijkstra, BFS, K-paths).")
@StackTrace(false)
final class AlgoOrchestratorEvent extends Event {

    @Label("Algorithm Name")
    @Description("Name of the active PathFinder (e.g. 'dijkstra', 'bfs')")
    /* default */ String algorithmName;

    @Label("Operation Type")
    @Description("SHORTEST_PATH | MULTI_TARGET_SHORTEST_PATH | K_SHORTEST_PATHS")
    /* default */ String operationType;

    @Label("Found")
    @Description("true if at least one path was found")
    /* default */ boolean found;

    @Label("Hop Count")
    @Description("Number of hops in the shortest path (or count of found paths for multi-target)")
    /* default */ int hopCount;

    @Label("Total Cost")
    @Description("Total cost/distance; +Infinity if not found")
    /* default */ double totalCost;

    /**
     * Allocates and begins a new event only if JFR is initialized and this event is enabled.
     * Returns {@code null} with zero allocation when JFR is inactive or the event is disabled.
     */
    /* default */ static AlgoOrchestratorEvent beginIfEnabled() {
        if (!FlightRecorder.isInitialized()) {
            return null;
        }
        AlgoOrchestratorEvent event = new AlgoOrchestratorEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }
}

