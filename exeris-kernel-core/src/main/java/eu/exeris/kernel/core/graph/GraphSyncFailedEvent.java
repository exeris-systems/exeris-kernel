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
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link GraphSyncService} when a dual-write synchronization fails
 * and EX-GRPH-5003 is about to be raised.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted immediately before throwing {@link eu.exeris.kernel.spi.exceptions.graph.GraphSyncException}
 * so that JFR recordings contain a timestamped record of every sync failure without requiring
 * exception stack trace capture (overhead: ~0 ns when JFR is disabled).
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.graph.SyncFailed")
@Label("Graph Sync Failed (EX-GRPH-5003)")
@Category({"Exeris Kernel", "Graph"})
@Description("Emitted when L1→L2 dual-write sync fails. Precedes GraphSyncException.")
@StackTrace(false)
final class GraphSyncFailedEvent extends Event {

    @Label("Edge / Label")
    @Description("The edge type or node label that failed to synchronise")
    /* default */ String edgeOrLabel;

    @Label("Detail")
    @Description("Static failure message from the upstream cause")
    /* default */ String detail;

    /* default */ static void emit(String edgeOrLabel, String detail) {
        GraphSyncFailedEvent event = new GraphSyncFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.edgeOrLabel = edgeOrLabel;
        event.detail      = detail;
        event.commit();
    }
}

