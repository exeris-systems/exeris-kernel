/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR event emitted for every {@link GraphSyncService} operation (node upsert/delete,
 * edge upsert/delete) at the L1 → L2 synchronisation boundary.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted for both success and failure paths. {@code elapsedNanos} is the wall-clock time of the
 * graph session, measured by the caller around the work and carried as a field. SRE tooling uses
 * this event to measure p99 sync latency and spot topology drift between L1 and L2.
 *
 * <p>Single-phase: the event is built and committed once, after the session has closed. A graph
 * session performs I/O, and a virtual thread that unmounts inside an open event window straddles
 * the carrier-bound event writer, so no window is open while the session runs.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.graph.SyncOperation")
@Label("Graph Sync Operation")
@Category({"Exeris Kernel", "Graph"})
@Description("Emitted for each L1→L2 graph synchronisation operation (node/edge CRUD).")
@StackTrace(false)
final class GraphSyncOperationEvent extends Event {

    @Label("Operation Type")
    @Description("One of: NODE_UPSERT, NODE_DELETE, EDGE_UPSERT, EDGE_DELETE")
    /* default */ String operationType;

    @Label("Edge / Label")
    @Description("Edge type or node label being synchronised")
    /* default */ String edgeOrLabel;

    @Label("Success")
    @Description("true if the graph write committed successfully")
    /* default */ boolean success;

    @Label("Elapsed")
    @Description("Wall-clock time of the graph session in nanoseconds")
    @Timespan(Timespan.NANOSECONDS)
    /* default */ long elapsedNanos;

    /**
     * Commits one event when Flight Recorder is initialized and this event is enabled. Nothing is
     * allocated when Flight Recorder is not initialized; one instance is allocated, and discarded, to
     * check that the event is enabled.
     *
     * @param operationType one of {@code NODE_UPSERT}, {@code NODE_DELETE}, {@code EDGE_UPSERT},
     *                      {@code EDGE_DELETE}
     * @param edgeOrLabel   the node label or edge type being synchronised
     * @param success       {@code true} if the graph write committed
     * @param startNanos    {@link System#nanoTime()} captured before the session was opened
     */
    /* default */ static void emit(String operationType, String edgeOrLabel,
                                   boolean success, long startNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        GraphSyncOperationEvent event = new GraphSyncOperationEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.operationType = operationType;
        event.edgeOrLabel = edgeOrLabel;
        event.success = success;
        event.elapsedNanos = System.nanoTime() - startNanos;
        event.commit();
    }
}
