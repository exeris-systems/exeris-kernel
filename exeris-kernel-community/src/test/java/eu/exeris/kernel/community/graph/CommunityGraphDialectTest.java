/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: {@link CommunityGraphDialect} — SQL generation correctness.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>dialectName() == "SQL/PGQ"</li>
 *   <li>buildMatchQuery() contains table name and $1 placeholder</li>
 *   <li>buildMultiHopQuery() produces valid recursive CTE with correct depth limits</li>
 *   <li>buildShortestPathQuery() references correct table and uses ARRAY path</li>
 *   <li>buildCreatePropertyGraph() embeds node/edge metadata</li>
 *   <li>buildCreateEdgeTable() contains DDL column definitions</li>
 *   <li>buildDropPropertyGraph() references the graph name</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityGraphDialect SQL generation")
class CommunityGraphDialectTest {

    private static final String GRAPH_NAME = "test_knowledge_graph";
    private CommunityGraphDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new CommunityGraphDialect(GRAPH_NAME);
    }

    @Nested
    @DisplayName("dialectName()")
    class DialectName {

        @Test
        @DisplayName("dialectName() returns 'SQL/PGQ'")
        void dialectNameIsSqlPgq() {
            assertThat(dialect.dialectName()).isEqualTo("SQL/PGQ");
        }
    }

    @Nested
    @DisplayName("buildMatchQuery()")
    class MatchQuery {

        @Test
        @DisplayName("contains the edge table name")
        void containsTableName() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMatchQuery(edge);
            assertThat(sql).contains(edge.tableName());
        }

        @Test
        @DisplayName("contains a ? placeholder (JDBC-style)")
        void containsPlaceholder() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMatchQuery(edge);
            assertThat(sql).contains("?");
        }

        @Test
        @DisplayName("selects target_id")
        void selectsTargetId() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            assertThat(dialect.buildMatchQuery(edge)).containsIgnoringCase("target_id");
        }

        @Test
        @DisplayName("buildMatchQuery SQL has exactly one positional ? (source_id only, no tenant filter)")
        void matchQueryHasOneParam() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMatchQuery(edge);
            long count = sql.chars().filter(c -> c == '?').count();
            assertThat(count).as("only source_id param; tenant_id filter removed").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("buildMultiHopQuery()")
    class MultiHopQuery {

        @Test
        @DisplayName("contains WITH RECURSIVE traversal CTE")
        void containsRecursiveCte() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 1, 3);
            assertThat(sql).containsIgnoringCase("WITH RECURSIVE");
        }

        @Test
        @DisplayName("contains maxHops depth limit")
        void containsMaxHopsDepthLimit() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 1, 5);
            assertThat(sql).contains("5"); // maxHops
        }

        @Test
        @DisplayName("contains minHops filter in WHERE clause")
        void containsMinHopsFilter() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 2, 5);
            assertThat(sql).contains("2"); // minHops
        }

        @Test
        @DisplayName("table name appears for both anchor and recursive clauses")
        void tableNameAppearsInBothBranches() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String table = edge.tableName();
            String sql = dialect.buildMultiHopQuery(edge, 1, 3);
            // Table name should appear at least twice (anchor + recursive branch)
            assertThat(sql.split(table, -1).length - 1)
                    .as("Table name must appear in both anchor and recursive branch of CTE")
                    .isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("buildMultiHopQuery anchor clause has one positional ? (source_id only, no tenant filter)")
        void multiHopQueryHasOneParamInAnchor() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 1, 2);
            long count = sql.chars().filter(c -> c == '?').count();
            assertThat(count).as("only source_id param in anchor; tenant_id filter removed").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("buildShortestPathQuery()")
    class ShortestPathQuery {

        @Test
        @DisplayName("contains WITH RECURSIVE path_search CTE")
        void containsRecursiveCte() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildShortestPathQuery(edge, 10);
            assertThat(sql).containsIgnoringCase("WITH RECURSIVE");
        }

        @Test
        @DisplayName("contains maxDepth in WHERE condition")
        void containsMaxDepth() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildShortestPathQuery(edge, 7);
            assertThat(sql).contains("7");
        }

        @Test
        @DisplayName("orders by weight ASC to find minimum cost path")
        void ordersAscending() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildShortestPathQuery(edge, 5);
            assertThat(sql).containsIgnoringCase("ORDER BY weight ASC");
        }
    }

    @Nested
    @DisplayName("buildCreatePropertyGraph()")
    class CreatePropertyGraph {

        @Test
        @DisplayName("contains graph name")
        void containsGraphName() {
            String sql = dialect.buildCreatePropertyGraph(
                    List.of(GraphNodeDescriptor.create("User", "users")),
                    List.of(GraphEdgeDescriptor.create("User", "FOLLOWS", "User"))
            );
            assertThat(sql).contains(GRAPH_NAME);
        }

        @Test
        @DisplayName("contains node table name in VERTEX TABLES")
        void containsVertexTable() {
            String sql = dialect.buildCreatePropertyGraph(
                    List.of(GraphNodeDescriptor.create("User", "users")),
                    List.of()
            );
            assertThat(sql).containsIgnoringCase("VERTEX TABLES");
        }

        @Test
        @DisplayName("contains edge type in EDGE TABLES")
        void containsEdgeTable() {
            String sql = dialect.buildCreatePropertyGraph(
                    List.of(GraphNodeDescriptor.create("User", "users")),
                    List.of(GraphEdgeDescriptor.create("User", "FOLLOWS", "User"))
            );
            assertThat(sql).containsIgnoringCase("EDGE TABLES");
            assertThat(sql).containsIgnoringCase("follows");
        }

        @Test
        @DisplayName("empty node/edge lists produce valid (minimal) DDL")
        void emptyListsProduceValidDdl() {
            String sql = dialect.buildCreatePropertyGraph(List.of(), List.of());
            assertThat(sql).isNotBlank();
            assertThat(sql).contains("CREATE PROPERTY GRAPH");
        }
    }

    @Nested
    @DisplayName("buildCreateEdgeTable()")
    class CreateEdgeTable {

        @Test
        @DisplayName("contains CREATE TABLE IF NOT EXISTS")
        void containsCreateTable() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).containsIgnoringCase("CREATE TABLE IF NOT EXISTS");
        }

        @Test
        @DisplayName("contains edge table name")
        void containsTableName() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).contains(edge.tableName());
        }

        @Test
        @DisplayName("DDL includes source_id, target_id, weight, properties columns")
        void containsRequiredColumns() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql)
                    .contains("source_id")
                    .contains("target_id")
                    .contains("weight")
                    .contains("properties");
        }

        @Test
        @DisplayName("tenant_id has DEFAULT nil-UUID sentinel")
        void tenantIdHasNilDefault() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).contains("DEFAULT '00000000-0000-0000-0000-000000000000'");
        }

        @Test
        @DisplayName("PRIMARY KEY is composite (source_id, target_id, tenant_id)")
        void primaryKeyIsComposite() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).containsIgnoringCase("PRIMARY KEY (source_id, target_id, tenant_id)");
        }
    }

    @Nested
    @DisplayName("buildDropPropertyGraph()")
    class DropPropertyGraph {

        @Test
        @DisplayName("contains DROP PROPERTY GRAPH IF EXISTS")
        void containsDropStatement() {
            String sql = dialect.buildDropPropertyGraph();
            assertThat(sql).containsIgnoringCase("DROP PROPERTY GRAPH IF EXISTS");
        }

        @Test
        @DisplayName("contains the graph name")
        void containsGraphName() {
            assertThat(dialect.buildDropPropertyGraph()).contains(GRAPH_NAME);
        }
    }
}

