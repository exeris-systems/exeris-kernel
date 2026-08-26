/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.List;
import java.util.Objects;

/**
 * Valhalla-Ready: Immutable descriptor of a graph node label.
 *
 * <p>Will be migrated to {@code value record} once JEP 401 is mainline.
 * Currently relies on C2 JIT Escape Analysis for scalarization on hot-paths.
 * Avoid identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>This record describes the <em>shape</em> of a node label — not the data itself.
 * It is created once during bootstrap (metadata discovery) and shared immutably
 * across all virtual threads via {@code ScopedValue} propagation.
 *
 * @param nodeLabel    the label in the graph (e.g. "User", "Product")
 * @param sourceTable  the relational table backing this node
 * @param idProperty   the property used as unique node identifier (default: "id")
 * @param properties   list of property names exposed in graph queries
 * @param syncToGraph  whether relational changes should be synced to graph
 *
 * @since 0.5.0
 */
public record GraphNodeDescriptor(
        String nodeLabel,
        String sourceTable,
        String idProperty,
        List<String> properties,
        boolean syncToGraph
) {
    /**
     * Compact constructor with validation.
     */
    public GraphNodeDescriptor {
        Objects.requireNonNull(nodeLabel, "nodeLabel");
        sourceTable = sourceTable != null ? sourceTable : "";
        idProperty = idProperty != null ? idProperty : "id";
        properties = properties != null ? List.copyOf(properties) : List.of();
    }

    /**
     * Quick factory for simple nodes.
     *
     * @param nodeLabel   node label
     * @param sourceTable source table name
     * @return node descriptor
     */
    public static GraphNodeDescriptor create(String nodeLabel, String sourceTable) {
        return new GraphNodeDescriptor(nodeLabel, sourceTable, "id", List.of(), true);
    }
}


