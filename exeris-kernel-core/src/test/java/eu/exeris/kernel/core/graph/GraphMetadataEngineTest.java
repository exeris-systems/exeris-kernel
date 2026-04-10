/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link GraphMetadataEngine} — annotation discovery, field extraction,
 * table name resolution.
 *
 * @since 0.5.0
 */
@DisplayName("L1: GraphMetadataEngine — annotation-driven metadata discovery")
class GraphMetadataEngineTest {

    // =========================================================================
    // Domain fixture classes
    // =========================================================================

    @GraphMetadataEngine.GraphNode(table = "users")
    private static final class UserDomain {
        private String handle;
        private int followerCount;
    }

    @GraphMetadataEngine.GraphNode(label = "Product", table = "products", idProperty = "sku",
            syncToGraph = false)
    private static final class ProductDomain {
        private String sku;
        private String name;
    }

    @GraphMetadataEngine.GraphEdge(type = "FOLLOWS", sourceNode = "User", targetNode = "User")
    private static final class FollowsRelation { }

    @GraphMetadataEngine.GraphEdge(type = "SIMILAR_TO", sourceNode = "Product",
            targetNode = "Product", weight = 0.8, bidirectional = true)
    private static final class SimilarityRelation {
        @GraphMetadataEngine.EdgeEndpoint(table = "product_similarity")
        private Object endpoint;
    }

    private static final class NonAnnotatedClass { }

    // =========================================================================
    // Node discovery
    // =========================================================================

    @Nested
    @DisplayName("discoverNodes()")
    class DiscoverNodes {

        @Test
        @DisplayName("extracts label from class name when @GraphNode.label is blank")
        void labelFromClassName() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(UserDomain.class));
            assertThat(nodes).hasSize(1);
            assertThat(nodes.getFirst().nodeLabel()).isEqualTo("UserDomain");
        }

        @Test
        @DisplayName("uses explicit label when @GraphNode.label is set")
        void explicitLabel() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(ProductDomain.class));
            assertThat(nodes.getFirst().nodeLabel()).isEqualTo("Product");
        }

        @Test
        @DisplayName("maps table, idProperty, syncToGraph from annotation")
        void annotationMapping() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(ProductDomain.class));
            GraphNodeDescriptor d = nodes.getFirst();
            assertThat(d.sourceTable()).isEqualTo("products");
            assertThat(d.idProperty()).isEqualTo("sku");
            assertThat(d.syncToGraph()).isFalse();
        }

        @Test
        @DisplayName("properties list contains declared instance field names")
        void fieldsExtracted() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(UserDomain.class));
            assertThat(nodes.getFirst().properties())
                    .containsExactlyInAnyOrder("handle", "followerCount");
        }

        @Test
        @DisplayName("non-annotated classes are silently skipped")
        void nonAnnotatedSkipped() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(NonAnnotatedClass.class));
            assertThat(nodes).isEmpty();
        }

        @Test
        @DisplayName("mixed list returns only annotated descriptors")
        void mixedList() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(UserDomain.class, NonAnnotatedClass.class, ProductDomain.class));
            assertThat(nodes).hasSize(2);
        }

        @Test
        @DisplayName("returned list is immutable")
        void resultIsImmutable() {
            List<GraphNodeDescriptor> nodes = GraphMetadataEngine.discoverNodes(
                    List.of(UserDomain.class));
            assertThatThrownBy(() -> nodes.add(GraphNodeDescriptor.create("X", "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null input → NullPointerException")
        void nullInputRejected() {
            assertThatThrownBy(() -> GraphMetadataEngine.discoverNodes(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Edge discovery
    // =========================================================================

    @Nested
    @DisplayName("discoverEdges()")
    class DiscoverEdges {

        @Test
        @DisplayName("extracts edge type, source, and target nodes")
        void edgeTypeExtracted() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(FollowsRelation.class));
            assertThat(edges).hasSize(1);
            GraphEdgeDescriptor d = edges.getFirst();
            assertThat(d.edgeType()).isEqualTo("FOLLOWS");
            assertThat(d.sourceNode()).isEqualTo("User");
            assertThat(d.targetNode()).isEqualTo("User");
        }

        @Test
        @DisplayName("table name derived from edge type when no @EdgeEndpoint override")
        void tableNameDerived() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(FollowsRelation.class));
            assertThat(edges.getFirst().tableName()).isEqualTo("follows_edges");
        }

        @Test
        @DisplayName("@EdgeEndpoint table override takes priority over derived name")
        void edgeEndpointOverride() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(SimilarityRelation.class));
            assertThat(edges.getFirst().tableName()).isEqualTo("product_similarity");
        }

        @Test
        @DisplayName("bidirectional=true maps to Direction.BOTH")
        void bidirectionalMapsToDirectionBoth() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(SimilarityRelation.class));
            assertThat(edges.getFirst().direction()).isEqualTo(GraphEdgeDescriptor.Direction.BOTH);
        }

        @Test
        @DisplayName("bidirectional=false maps to Direction.OUTGOING")
        void unidirectionalMapsToOutgoing() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(FollowsRelation.class));
            assertThat(edges.getFirst().direction()).isEqualTo(GraphEdgeDescriptor.Direction.OUTGOING);
        }

        @Test
        @DisplayName("weight annotation value is preserved")
        void weightPreserved() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(SimilarityRelation.class));
            assertThat(edges.getFirst().weight()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("returned list is immutable")
        void resultIsImmutable() {
            List<GraphEdgeDescriptor> edges = GraphMetadataEngine.discoverEdges(
                    List.of(FollowsRelation.class));
            assertThatThrownBy(() -> edges.add(GraphEdgeDescriptor.create("A", "B", "C")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null input → NullPointerException")
        void nullInputRejected() {
            assertThatThrownBy(() -> GraphMetadataEngine.discoverEdges(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

