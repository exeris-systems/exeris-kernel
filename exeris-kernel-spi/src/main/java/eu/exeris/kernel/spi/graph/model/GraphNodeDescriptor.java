/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable descriptor of the <em>shape</em> of a graph node label — not the data itself —
 * created once during bootstrap (metadata discovery) and shared immutably across all
 * virtual threads via {@code ScopedValue} propagation.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Structured so it can be migrated to a {@code value record} once JEP 401 is mainline;
 * until then it relies on C2 JIT escape analysis for scalarization on hot paths. Avoid
 * identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * @param nodeLabel    the label in the graph (e.g. "User", "Product")
 * @param sourceTable  the relational table backing this node
 * @param idProperty   the property used as unique node identifier (default: "id")
 * @param properties   list of property names exposed in graph queries
 * @param syncToGraph  whether relational changes should be synced to graph
 *
 * @since 0.5
 */
public record GraphNodeDescriptor(
        String nodeLabel,
        String sourceTable,
        String idProperty,
        List<String> properties,
        boolean syncToGraph
) {
    /**
     * Rejects a {@code null} {@code nodeLabel}, and fills in {@code sourceTable},
     * {@code idProperty} and {@code properties} defaults when the caller passes
     * {@code null} for any of them.
     *
     * @throws NullPointerException if {@code nodeLabel} is {@code null}
     */
    public GraphNodeDescriptor {
        Objects.requireNonNull(nodeLabel, "nodeLabel");
        sourceTable = sourceTable != null ? sourceTable : "";
        idProperty = idProperty != null ? idProperty : "id";
        properties = properties != null ? List.copyOf(properties) : List.of();
    }

    /**
     * Returns a node descriptor for {@code nodeLabel} backed by {@code sourceTable}, with
     * {@code idProperty} defaulted to {@code "id"}, an empty property list, and
     * {@code syncToGraph} enabled.
     *
     * @param nodeLabel   node label
     * @param sourceTable source table name
     * @return node descriptor
     */
    public static GraphNodeDescriptor create(String nodeLabel, String sourceTable) {
        return new GraphNodeDescriptor(nodeLabel, sourceTable, "id", List.of(), true);
    }
}


