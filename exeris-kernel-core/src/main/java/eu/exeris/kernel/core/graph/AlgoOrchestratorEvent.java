/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.graph.model.PathResult;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JFR event emitted by {@link AlgoOrchestrator} for every algorithm invocation.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted for both found and not-found results. {@code elapsedNanos} is the wall-clock time of
 * the whole path-finder delegation, measured by the caller around the work and carried as a field.
 *
 * <p>Single-phase: the event is built and committed once, after the work. No event window is open
 * while the path finder runs, because a path finder's weight function can reach storage and block,
 * and a virtual thread that unmounts inside an open window straddles the carrier-bound event writer.
 *
 * @since 0.5
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

    @Label("Elapsed")
    @Description("Wall-clock time of the path-finder delegation in nanoseconds")
    @Timespan(Timespan.NANOSECONDS)
    /* default */ long elapsedNanos;

    /**
     * Commits one event for a single-path search.
     *
     * @param algorithmName name of the active path finder
     * @param operationType {@code SHORTEST_PATH}
     * @param result        the path finder's result
     * @param startNanos    {@link System#nanoTime()} captured before the delegation
     */
    /* default */ static void emitSingle(String algorithmName, String operationType,
                                         PathResult result, long startNanos) {
        AlgoOrchestratorEvent event = newEventIfEnabled();
        if (event == null) {
            return;
        }
        event.found = result.found();
        event.hopCount = result.hopCount();
        event.totalCost = result.totalCost();
        event.commitWith(algorithmName, operationType, startNanos);
    }

    /**
     * Commits one event for a multi-target search.
     *
     * @param algorithmName name of the active path finder
     * @param results       the path finder's results, keyed by target
     * @param startNanos    {@link System#nanoTime()} captured before the delegation
     */
    /* default */ static void emitMultiTarget(String algorithmName,
                                              Map<UUID, PathResult> results, long startNanos) {
        AlgoOrchestratorEvent event = newEventIfEnabled();
        if (event == null) {
            return;
        }
        long foundCount = results.values().stream().filter(PathResult::found).count();
        event.found = foundCount > 0;
        event.hopCount = (int) foundCount;
        event.totalCost = Double.NaN;
        event.commitWith(algorithmName, "MULTI_TARGET_SHORTEST_PATH", startNanos);
    }

    /**
     * Commits one event for a K-shortest-paths search.
     *
     * @param algorithmName name of the active path finder
     * @param results       the path finder's results, cheapest first
     * @param startNanos    {@link System#nanoTime()} captured before the delegation
     */
    /* default */ static void emitKPaths(String algorithmName,
                                         List<PathResult> results, long startNanos) {
        AlgoOrchestratorEvent event = newEventIfEnabled();
        if (event == null) {
            return;
        }
        event.found = !results.isEmpty();
        event.hopCount = results.size();
        event.totalCost = results.isEmpty() ? Double.POSITIVE_INFINITY : results.getFirst().totalCost();
        event.commitWith(algorithmName, "K_SHORTEST_PATHS", startNanos);
    }

    private void commitWith(String algorithm, String operation, long startNanos) {
        this.algorithmName = algorithm;
        this.operationType = operation;
        this.elapsedNanos = System.nanoTime() - startNanos;
        commit();
    }

    /**
     * Returns a fresh event when Flight Recorder is initialized and this event is enabled, and
     * {@code null} otherwise. Nothing is allocated when Flight Recorder is not initialized.
     */
    private static AlgoOrchestratorEvent newEventIfEnabled() {
        if (!FlightRecorder.isInitialized()) {
            return null;
        }
        AlgoOrchestratorEvent event = new AlgoOrchestratorEvent();
        return event.isEnabled() ? event : null;
    }
}
