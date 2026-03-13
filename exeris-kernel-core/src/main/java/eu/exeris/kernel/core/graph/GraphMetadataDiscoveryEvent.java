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
 * JFR event emitted by {@link GraphMetadataEngine} after each discovery pass.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.graph.MetadataDiscovery")
@Label("Graph Metadata Discovery")
@Category({"Exeris Kernel", "Graph"})
@Description("Emitted once per discovery pass (node or edge) during graph bootstrap.")
@StackTrace(false)
final class GraphMetadataDiscoveryEvent extends Event {

    @Label("Descriptor Type")
    @Description("'NODE' or 'EDGE'")
    /* default */ String descriptorType;

    @Label("Discovered Count")
    @Description("Number of annotated classes that produced descriptors")
    /* default */ int discoveredCount;

    @Label("Scanned Count")
    @Description("Total number of classes scanned")
    /* default */ int scannedCount;

    /* default */ static void emit(String descriptorType, int discoveredCount, int scannedCount) {
        GraphMetadataDiscoveryEvent event = new GraphMetadataDiscoveryEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.descriptorType  = descriptorType;
        event.discoveredCount = discoveredCount;
        event.scannedCount    = scannedCount;
        event.commit();
    }
}

