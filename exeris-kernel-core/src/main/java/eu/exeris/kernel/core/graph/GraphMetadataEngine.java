/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core: Annotation-driven graph metadata discovery engine.
 *
 * <h2>Responsibility (graph.md §Metadata Engine)</h2>
 * <p>Scans domain classes annotated with {@link GraphNode} and {@link GraphEdge} and
 * produces the canonical {@link GraphNodeDescriptor} and {@link GraphEdgeDescriptor}
 * lists consumed by {@link eu.exeris.kernel.spi.graph.GraphEngine#registerNodes(List)}
 * and {@link eu.exeris.kernel.spi.graph.GraphEngine#registerEdges(List)}.
 *
 * <h2>Reflection Policy</h2>
 * <p>Reflection is used exclusively during the <b>bootstrap phase</b> (before the kernel
 * enters {@code READY} state). It is never called on the hot path. This exception to the
 * "no reflection" rule is intentional and documented here — bootstrap-time annotation
 * scanning is the industry-standard mechanism for zero-configuration graph schema
 * registration.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>This class imports only {@code exeris-kernel-spi}. It produces pure SPI value
 * records with no knowledge of JDBC, Bolt, or any backend.
 *
 * @since 0.5
 */
public final class GraphMetadataEngine {

    /**
     * Marks a domain class as a graph node, enabling automatic discovery via
     * {@link GraphMetadataEngine#discoverNodes(List)}.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GraphNode {
        /** The node label in the graph (e.g. "User"). Defaults to the simple class name. */
        String label() default "";

        /** The backing relational table (e.g. "users"). Required. */
        String table();

        /** The property name used as unique node identifier. Default: "id". */
        String idProperty() default "id";

        /** Whether relational changes should be synced to the graph. Default: true. */
        boolean syncToGraph() default true;
    }

    /**
     * Marks a domain class as defining a graph edge relationship.
     * Fields annotated with {@link EdgeEndpoint} may optionally override the default table name.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GraphEdge {
        /** The edge type label (e.g. "FOLLOWS"). Required. */
        String type();

        /** The source node label (e.g. "User"). Required. */
        String sourceNode();

        /** The target node label (e.g. "User"). Required. */
        String targetNode();

        /** Default edge weight. Default: 1.0. */
        double weight() default 1.0;

        /** Whether the edge is bidirectional. Default: false. */
        boolean bidirectional() default false;
    }

    /**
     * Marks a field on a {@link GraphEdge}-annotated class as a graph edge endpoint.
     * Used to override the default table name derivation.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface EdgeEndpoint {
        /** Custom backing table name for this edge. If blank, derived from edge type. */
        String table() default "";
    }

    private GraphMetadataEngine() {
        // utility — no instances
    }

    /**
     * Scans the given classes and extracts all {@link GraphNodeDescriptor}s.
     *
     * <p>Only classes annotated with {@link GraphNode} are processed. Non-annotated
     * classes are silently ignored. Called once during bootstrap — reflection overhead
     * is acceptable at this phase.
     *
     * @param domainClasses list of candidate domain classes to scan (must not be {@code null})
     * @return immutable list of node descriptors, one per {@link GraphNode}-annotated class
     */
    public static List<GraphNodeDescriptor> discoverNodes(List<Class<?>> domainClasses) {
        Objects.requireNonNull(domainClasses, "domainClasses must not be null");
        List<GraphNodeDescriptor> result = new ArrayList<>(domainClasses.size());
        for (Class<?> cls : domainClasses) {
            GraphNode annotation = cls.getAnnotation(GraphNode.class);
            if (annotation == null) {
                continue;
            }
            String label = annotation.label().isBlank()
                    ? cls.getSimpleName()
                    : annotation.label();
            List<String> properties = extractFieldNames(cls);
            result.add(new GraphNodeDescriptor(
                    label,
                    annotation.table(),
                    annotation.idProperty(),
                    properties,
                    annotation.syncToGraph()
            ));
        }
        GraphMetadataDiscoveryEvent.emit("NODE", result.size(), domainClasses.size());
        return List.copyOf(result);
    }

    /**
     * Scans the given classes and extracts all {@link GraphEdgeDescriptor}s.
     *
     * <p>Only classes annotated with {@link GraphEdge} are processed. Non-annotated
     * classes are silently ignored. Called once during bootstrap.
     *
     * @param domainClasses list of candidate domain classes to scan (must not be {@code null})
     * @return immutable list of edge descriptors, one per {@link GraphEdge}-annotated class
     */
    public static List<GraphEdgeDescriptor> discoverEdges(List<Class<?>> domainClasses) {
        Objects.requireNonNull(domainClasses, "domainClasses must not be null");
        List<GraphEdgeDescriptor> result = new ArrayList<>(domainClasses.size());
        for (Class<?> cls : domainClasses) {
            GraphEdge annotation = cls.getAnnotation(GraphEdge.class);
            if (annotation == null) {
                continue;
            }
            String tableName = resolveEdgeTable(cls, annotation);
            result.add(new GraphEdgeDescriptor(
                    annotation.sourceNode(),
                    annotation.type(),
                    annotation.targetNode(),
                    annotation.weight(),
                    annotation.bidirectional(),
                    annotation.bidirectional()
                            ? GraphEdgeDescriptor.Direction.BOTH
                            : GraphEdgeDescriptor.Direction.OUTGOING,
                    tableName
            ));
        }
        GraphMetadataDiscoveryEvent.emit("EDGE", result.size(), domainClasses.size());
        return List.copyOf(result);
    }

    /**
     * Resolves the backing table name for an edge descriptor.
     *
     * <p>Priority: a single non-blank {@link EdgeEndpoint#table()} override on a field
     * → derived from {@link GraphEdge#type()} via lower-case + "_edges" suffix.
     *
     * @throws IllegalStateException if more than one field declares a non-blank
     *                               {@link EdgeEndpoint#table()} override
     */
    private static String resolveEdgeTable(Class<?> cls, GraphEdge annotation) {
        String resolvedTable = null;
        String resolvedFieldName = null;
        for (Field field : cls.getDeclaredFields()) {
            EdgeEndpoint endpoint = field.getAnnotation(EdgeEndpoint.class);
            if (endpoint == null || endpoint.table().isBlank()) {
                continue;
            }
            if (resolvedTable != null) {
                throw new IllegalStateException(
                        "Multiple @EdgeEndpoint(table=...) overrides found in "
                        + cls.getName() + ": " + resolvedFieldName + ", " + field.getName());
            }
            resolvedTable = endpoint.table();
            resolvedFieldName = field.getName();
        }
        if (resolvedTable != null) {
            return resolvedTable;
        }
        return annotation.type().toLowerCase(java.util.Locale.ROOT) + "_edges";
    }

    /**
     * Extracts non-static, non-synthetic field names from the class as graph node properties.
     */
    private static List<String> extractFieldNames(Class<?> cls) {
        Field[] fields = cls.getDeclaredFields();
        List<String> names = new ArrayList<>(fields.length);
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(modifiers) && !field.isSynthetic()) {
                names.add(field.getName());
            }
        }
        return List.copyOf(names);
    }
}
