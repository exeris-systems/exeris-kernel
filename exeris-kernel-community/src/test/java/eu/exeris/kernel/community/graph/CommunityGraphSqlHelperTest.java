/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSessionLiveContractTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Community: CommunityGraphSqlHelper unit tests")
@ExtendWith(MockitoExtension.class)
class CommunityGraphSqlHelperTest {

    @Mock
    private PersistenceConnection connection;

    @Mock
    private PersistenceStatement statement;

    @Test
    @DisplayName("deleteNode for Product label builds expected CTEs and binds parameters")
    void deleteNodeProductBuildsExpectedCtesAndBinds() {
        CommunityGraphDialect dialect = new CommunityGraphDialect("test_graph");
        List<GraphEdgeDescriptor> edges = AbstractGraphSessionLiveContractTck.edgeTypes();
        CommunityGraphSqlHelper helper = new CommunityGraphSqlHelper(dialect, () -> connection, edges);

        String sql = helper.buildDeleteNodeSql("Product");

        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_purchased_edges WHERE target_id = (SELECT id FROM arg)");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_viewed_edges WHERE target_id = (SELECT id FROM arg)");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_similar_to_edges WHERE source_id = (SELECT id FROM arg)"
                        + " OR target_id = (SELECT id FROM arg)");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_linked_edges WHERE source_id = (SELECT id FROM arg)"
                        + " OR target_id = (SELECT id FROM arg)");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_never_seeded_edges WHERE source_id = (SELECT id FROM arg)"
                        + " OR target_id = (SELECT id FROM arg)");
        assertThat(sql).contains(
                "DELETE FROM graph_nodes WHERE id = (SELECT id FROM arg) AND label = $2");

        assertThat(sql).containsOnlyOnce("$1").containsOnlyOnce("$2");
        assertThat(sql.indexOf("$1")).isLessThan(sql.indexOf("$2"));

        when(connection.prepare(sql)).thenReturn(statement);
        UUID nodeId = UUID.randomUUID();
        helper.deleteNode("Product", nodeId);

        verify(connection, times(1)).prepare(sql);
        verify(statement, times(1)).bindUuid(0, nodeId);
        verify(statement, times(1)).bindString(1, "Product");
        verify(statement, times(1)).executeUpdate();
    }

    @Test
    @DisplayName("deleteNode for User label builds source_id predicates and excludes other tables")
    void deleteNodeUserBuildsSourcePredicatesOnly() {
        CommunityGraphDialect dialect = new CommunityGraphDialect("test_graph");
        List<GraphEdgeDescriptor> edges = AbstractGraphSessionLiveContractTck.edgeTypes();
        CommunityGraphSqlHelper helper = new CommunityGraphSqlHelper(dialect, () -> connection, edges);

        String sql = helper.buildDeleteNodeSql("User");

        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_purchased_edges WHERE source_id = (SELECT id FROM arg)");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_viewed_edges WHERE source_id = (SELECT id FROM arg)");
        assertThat(sql).doesNotContain("tck_similar_to_edges");
        assertThat(sql).doesNotContain("tck_linked_edges");
        assertThat(sql).doesNotContain("tck_never_seeded_edges");
        assertThat(sql).doesNotContain("target_id = (SELECT id FROM arg)");
    }

    @Test
    @DisplayName("deleteNode for unknown label contains no del_e CTEs")
    void deleteNodeUnknownLabelContainsNoDelECtes() {
        CommunityGraphDialect dialect = new CommunityGraphDialect("test_graph");
        List<GraphEdgeDescriptor> edges = AbstractGraphSessionLiveContractTck.edgeTypes();
        CommunityGraphSqlHelper helper = new CommunityGraphSqlHelper(dialect, () -> connection, edges);

        String sql = helper.buildDeleteNodeSql("Unknown");

        assertThat(sql).doesNotContain("del_e");
        assertThat(sql.trim()).isEqualTo(
                "WITH arg (id) AS (SELECT CAST($1 AS uuid))\nDELETE FROM graph_nodes WHERE id = (SELECT id FROM arg) AND label = $2");
    }

    @Test
    @DisplayName("deleteNode deduplicates edge descriptors sharing the same backing table")
    void deleteNodeDeduplicatesDescriptorsSharingTable() {
        CommunityGraphDialect dialect = new CommunityGraphDialect("test_graph");
        List<GraphEdgeDescriptor> edges = new ArrayList<>(AbstractGraphSessionLiveContractTck.edgeTypes());
        GraphEdgeDescriptor duplicate = new GraphEdgeDescriptor(
                "Product", "PURCHASED_ALT", "User", 1.0, false,
                GraphEdgeDescriptor.Direction.OUTGOING, "tck_purchased_edges");
        edges.add(duplicate);
        CommunityGraphSqlHelper helper = new CommunityGraphSqlHelper(dialect, () -> connection, edges);

        String sql = helper.buildDeleteNodeSql("Product");

        assertThat(sql).containsOnlyOnce("DELETE FROM tck_purchased_edges WHERE ");
        assertThat(sql).containsOnlyOnce(
                "DELETE FROM tck_purchased_edges WHERE source_id = (SELECT id FROM arg)"
                        + " OR target_id = (SELECT id FROM arg)");
    }

    @Test
    @DisplayName("deleteNode rejects an edge table name that is not a SQL identifier before preparing anything")
    void deleteNodeRejectsInvalidEdgeTableNameBeforePrepare() {
        CommunityGraphDialect dialect = new CommunityGraphDialect("test_graph");
        List<GraphEdgeDescriptor> edges = new ArrayList<>(AbstractGraphSessionLiveContractTck.edgeTypes());
        edges.add(new GraphEdgeDescriptor(
                "Product", "MALICIOUS", "User", 1.0, false,
                GraphEdgeDescriptor.Direction.OUTGOING, "t; DROP TABLE graph_nodes"));
        CommunityGraphSqlHelper helper = new CommunityGraphSqlHelper(dialect, () -> connection, edges);
        UUID nodeId = UUID.randomUUID();

        assertThatThrownBy(() -> helper.deleteNode("Product", nodeId))
                .isInstanceOf(GraphQueryException.class);

        verify(connection, never()).prepare(any());
    }
}
