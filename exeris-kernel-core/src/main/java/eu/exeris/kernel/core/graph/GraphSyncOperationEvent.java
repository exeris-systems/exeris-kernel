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

/**
 * JFR event emitted for every {@link GraphSyncService} operation (node upsert/delete,
 * edge upsert/delete) at the L1 → L2 synchronisation boundary.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted for both success and failure paths. Duration is automatically captured
 * via {@link Event#begin()} / {@link Event#commit()}. SRE tooling uses this event
 * to measure p99 sync latency and spot topology drift between L1 and L2.
 *
 * @since 0.5.0
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

    /**
     * Allocates and begins a new event only if JFR is initialized and this event is enabled.
     * Returns {@code null} with zero allocation when JFR is not initialized; allocates one instance
     * to check enablement and returns {@code null} (discarding the instance) if the event is disabled.
     */
    /* default */ static GraphSyncOperationEvent beginIfEnabled() {
        if (!FlightRecorder.isInitialized()) {
            return null;
        }
        GraphSyncOperationEvent event = new GraphSyncOperationEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }
}

