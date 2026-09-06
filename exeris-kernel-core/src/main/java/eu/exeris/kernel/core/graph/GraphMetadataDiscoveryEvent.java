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
 * JFR event emitted by {@link GraphMetadataEngine} after each discovery pass.
 *
 * @since 0.5
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

    /**
     * Emits the metadata-discovery event if JFR recording is active; a no-op otherwise.
     *
     * @param descriptorType  {@code "NODE"} or {@code "EDGE"} — which discovery pass ran
     * @param discoveredCount number of annotated classes that produced a descriptor
     * @param scannedCount    total number of candidate classes scanned
     */
    /* default */ static void emit(String descriptorType, int discoveredCount, int scannedCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
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

