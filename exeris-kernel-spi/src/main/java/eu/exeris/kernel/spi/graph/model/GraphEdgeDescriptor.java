/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.Objects;

/**
 * Valhalla-Ready: Immutable descriptor of a graph edge type.
 *
 * <p>Will be migrated to {@code value record} once JEP 401 is mainline.
 * Currently relies on C2 JIT Escape Analysis for scalarization on hot-paths.
 * Avoid identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>This record describes the <em>shape</em> of an edge type — not the data.
 * Created once during metadata discovery and shared immutably via {@code ScopedValue}.
 *
 * @param sourceNode    the source node label (e.g. "User")
 * @param edgeType      the edge type (e.g. "FOLLOWS", "SIMILAR_TO")
 * @param targetNode    the target node label (e.g. "User")
 * @param weight        default edge weight
 * @param bidirectional whether the edge is bidirectional
 * @param direction     edge direction (OUTGOING, INCOMING, BOTH)
 * @param tableName     pre-computed relational table name (e.g. "follows_edges")
 *
 * @since 0.5.0
 */
public record GraphEdgeDescriptor(
        String sourceNode,
        String edgeType,
        String targetNode,
        double weight,
        boolean bidirectional,
        Direction direction,
        String tableName
) {
    /**
     * Edge direction in the graph.
     */
    public enum Direction {
        /** Source → Target. */
        OUTGOING,
        /** Target → Source. */
        INCOMING,
        /** Both directions. */
        BOTH
    }

    /**
     * Compact constructor with validation.
     */
    public GraphEdgeDescriptor {
        Objects.requireNonNull(sourceNode, "sourceNode");
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(targetNode, "targetNode");
        direction = direction != null ? direction : Direction.OUTGOING;
        tableName = tableName != null ? tableName : toSnakeCase(edgeType) + "_edges";
    }

    /**
     * Quick factory for simple edges.
     *
     * @param sourceNode source node label
     * @param edgeType   edge type
     * @param targetNode target node label
     * @return edge descriptor with defaults
     */
    public static GraphEdgeDescriptor create(String sourceNode, String edgeType, String targetNode) {
        return new GraphEdgeDescriptor(sourceNode, edgeType, targetNode,
                1.0, false, Direction.OUTGOING, null);
    }

    /**
     * Converts CamelCase or UPPER_CASE to snake_case.
     * Computed once at construction — zero allocation on hot-path.
     *
     * <p>Edge cases handled:
     * <ul>
     *   <li>{@code "FOLLOWS"}    → {@code "follows"}    (all-caps, no underscores)</li>
     *   <li>{@code "FollowsEdge"} → {@code "follows_edge"}</li>
     *   <li>{@code "FOLLOWS_EDGE"} → {@code "follows_edge"} (already snake, lower-cased)</li>
     *   <li>{@code "XMLParser"}  → {@code "xml_parser"}  (acronym + word boundary)</li>
     * </ul>
     */
    private static String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.contains("_")) {
            return input.toLowerCase(java.util.Locale.ROOT);
        }
        if (isAllUpperCase(input)) {
            return input.toLowerCase(java.util.Locale.ROOT);
        }
        StringBuilder result = new StringBuilder(input.length() + 4);
        for (int i = 0; i < input.length(); i++) {
            char chr = input.charAt(i);
            if (Character.isUpperCase(chr) && i > 0 && isWordBoundary(input, i)) {
                result.append('_');
            }
            result.append(Character.toLowerCase(chr));
        }
        return result.toString();
    }

    /**
     * Returns {@code true} when position {@code idx} represents a CamelCase word boundary.
     * Handles three cases:
     * <ol>
     *   <li>Standard camel boundary: previous char is lowercase
     *       ({@code "followsEdge"} → boundary before {@code 'E'}).</li>
     *   <li>Acronym-to-word transition: previous char is uppercase AND next char
     *       is lowercase ({@code "XMLParser"} → boundary before {@code 'P'}).</li>
     *   <li>Trailing acronym: consecutive uppercase letters at the end of the string
     *       do NOT produce boundaries ({@code "FollowsXML"} → {@code "follows_xml"},
     *       not {@code "follows_x_m_l"}).</li>
     * </ol>
     */
    private static boolean isWordBoundary(String input, int idx) {
        char prev = input.charAt(idx - 1);
        return Character.isLowerCase(prev)
                || (Character.isUpperCase(prev)
                    && idx + 1 < input.length()
                    && Character.isLowerCase(input.charAt(idx + 1)));
    }

    /** Returns {@code true} if every letter in {@code input} is uppercase. */
    private static boolean isAllUpperCase(String input) {
        for (int i = 0; i < input.length(); i++) {
            char chr = input.charAt(i);
            if (Character.isLetter(chr) && Character.isLowerCase(chr)) {
                return false;
            }
        }
        return true;
    }
}



