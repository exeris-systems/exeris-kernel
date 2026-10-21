/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        @DisplayName("contains a $1 placeholder (PostgreSQL-style)")
        void containsPlaceholder() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMatchQuery(edge);
            assertThat(sql).contains("$1");
        }

        @Test
        @DisplayName("selects target_id")
        void selectsTargetId() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            assertThat(dialect.buildMatchQuery(edge)).containsIgnoringCase("target_id");
        }

        @Test
        @DisplayName("buildMatchQuery SQL has exactly one positional param $1 (source_id only)")
        void matchQueryHasOneParam() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMatchQuery(edge);
            assertThat(sql).as("source_id param").contains("$1");
            assertThat(sql).as("no second param").doesNotContain("$2");
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
        @DisplayName("a ranged hop is one recursive CTE over its table bounded by maxRepeat")
        void rangedHopIsOneRecursiveCteBoundedByMaxRepeat() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 1, 3);
            assertThat(sql)
                    .contains("UNION SELECT")
                    .contains("WHERE h.depth < 3")
                    .contains("depth >= 1")
                    .doesNotContain("UNION ALL");
        }

        @Test
        @DisplayName("buildMultiHopQuery anchor clause has one positional param $1 (source_id only)")
        void multiHopQueryHasOneParamInAnchor() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildMultiHopQuery(edge, 1, 2);
            assertThat(sql).as("source_id param").contains("$1");
            assertThat(sql).as("no second param").doesNotContain("$2");
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
        @DisplayName("tenant_id column is not present")
        void tenantIdNotPresent() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).doesNotContain("tenant_id");
        }

        @Test
        @DisplayName("PRIMARY KEY is (source_id, target_id)")
        void primaryKeyIsComposite() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            String sql = dialect.buildCreateEdgeTable(edge);
            assertThat(sql).containsIgnoringCase("PRIMARY KEY (source_id, target_id)");
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

    @Nested
    @DisplayName("buildPathQuery() — SQL mode")
    class PathQuery {

        /** Creates the nested test group. */
        PathQuery() {
            super();
        }

        @Test
        @DisplayName("worked example generates exact SQL query modulo whitespace")
        void workedExampleGeneratesExpectedSql() {
            GraphEdgeDescriptor purchased = new GraphEdgeDescriptor(
                    "User", "PURCHASED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_purchased_edges");
            GraphEdgeDescriptor similarTo = new GraphEdgeDescriptor(
                    "Product", "SIMILAR_TO", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_similar_to_edges");
            List<GraphHop> hops = List.of(
                    GraphHop.of(purchased),
                    new GraphHop(similarTo, 1, 2));

            String expected = """
                    WITH RECURSIVE
                    f0 (id) AS (SELECT CAST($1 AS uuid)),
                    f1 (id) AS (SELECT DISTINCT e.target_id AS id FROM f0 p INNER JOIN tck_purchased_edges e ON e.source_id = p.id),
                    h2 (id, depth) AS (SELECT p.id, 0 FROM f1 p UNION SELECT e.target_id, h.depth + 1 FROM h2 h INNER JOIN tck_similar_to_edges e ON e.source_id = h.id WHERE h.depth < 2),
                    f2 (id) AS (SELECT DISTINCT id FROM h2 WHERE depth >= 1)
                    SELECT id FROM f2
                    """;
            String actual = dialect.buildPathQuery(hops);
            assertThat(actual.replaceAll("\\s+", " ").trim())
                    .isEqualTo(expected.replaceAll("\\s+", " ").trim());
        }

        @Test
        @DisplayName("INCOMING hop SQL contains e.target_id = p.id and e.source_id AS id")
        void incomingHopSql() {
            GraphEdgeDescriptor incoming = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.INCOMING, "tck_linked_edges");
            String sql = dialect.buildPathQuery(List.of(GraphHop.of(incoming)));
            assertThat(sql)
                    .contains("e.target_id = p.id")
                    .contains("e.source_id AS id");
        }

        @Test
        @DisplayName("BOTH hop SQL contains OR e.target_id")
        void bothHopSql() {
            GraphEdgeDescriptor both = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.BOTH, "tck_linked_edges");
            String sql = dialect.buildPathQuery(List.of(GraphHop.of(both)));
            assertThat(sql).contains("OR e.target_id");
        }

        @Test
        @DisplayName("bidirectional OUTGOING descriptor generates the BOTH form")
        void bidirectionalOutgoingGeneratesBothForm() {
            GraphEdgeDescriptor bi = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, true,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_linked_edges");
            String sql = dialect.buildPathQuery(List.of(GraphHop.of(bi)));
            assertThat(sql).contains("OR e.target_id");
        }

        @Test
        @DisplayName("sum of maxRepeat exceeding 64 throws IllegalArgumentException")
        void hopsSumExceedingLimitThrows() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            List<GraphHop> hops = List.of(new GraphHop(edge, 1, 65));
            assertThatThrownBy(() -> dialect.buildPathQuery(hops))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sum of maxRepeat equal to 64 is accepted")
        void hopsSumAtLimitAccepted() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            List<GraphHop> hops = List.of(new GraphHop(edge, 1, 64));
            assertThatCode(() -> dialect.buildPathQuery(hops))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("empty hops list throws IllegalArgumentException")
        void emptyHopsThrows() {
            List<GraphHop> empty = List.of();
            assertThatThrownBy(() -> dialect.buildPathQuery(empty))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("invalid SQL table name throws IllegalArgumentException")
        void badTableNameThrows() {
            GraphEdgeDescriptor badTable = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "bad-name");
            List<GraphHop> hops = List.of(GraphHop.of(badTable));
            assertThatThrownBy(() -> dialect.buildPathQuery(hops))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("buildPathQuery() — Cypher mode")
    class CypherPathQuery {

        private final CommunityGraphDialect cypherDialect = new CommunityGraphDialect("g", "neo4j");

        /** Creates the nested test group. */
        CypherPathQuery() {
            super();
        }

        @Test
        @DisplayName("worked example generates exact Cypher query modulo whitespace")
        void workedExampleGeneratesExpectedCypher() {
            GraphEdgeDescriptor purchased = new GraphEdgeDescriptor(
                    "User", "PURCHASED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_purchased_edges");
            GraphEdgeDescriptor similarTo = new GraphEdgeDescriptor(
                    "Product", "SIMILAR_TO", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_similar_to_edges");
            List<GraphHop> hops = List.of(
                    GraphHop.of(purchased),
                    new GraphHop(similarTo, 1, 2));

            String expected = """
                    MATCH (source) WHERE source.id = $sourceId
                    WITH DISTINCT source
                    MATCH (source)-[:PURCHASED]->(n1)
                    WITH DISTINCT n1
                    MATCH (n1)-[:SIMILAR_TO]->(n2)
                    WITH DISTINCT n2
                    MATCH (n2)-[:SIMILAR_TO*0..1]->(target)
                    WITH DISTINCT target
                    RETURN DISTINCT target.id AS id
                    """;
            String actual = cypherDialect.buildPathQuery(hops);
            assertThat(actual.replaceAll("\\s+", " ").trim())
                    .isEqualTo(expected.replaceAll("\\s+", " ").trim());
        }

        @Test
        @DisplayName("INCOMING hop Cypher contains <-[:LINKED]-")
        void cypherIncomingArrow() {
            GraphEdgeDescriptor incoming = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.INCOMING, "tck_linked_edges");
            String cypher = cypherDialect.buildPathQuery(List.of(GraphHop.of(incoming)));
            assertThat(cypher).contains("<-[:LINKED]-");
        }

        @Test
        @DisplayName("BOTH hop Cypher contains -[:LINKED]-(")
        void cypherBothArrow() {
            GraphEdgeDescriptor both = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.BOTH, "tck_linked_edges");
            String cypher = cypherDialect.buildPathQuery(List.of(GraphHop.of(both)));
            assertThat(cypher).contains("-[:LINKED]-(");
        }

        @Test
        @DisplayName("bidirectional OUTGOING descriptor generates the BOTH arrow in Cypher")
        void bidirectionalOutgoingGeneratesBothArrow() {
            GraphEdgeDescriptor bi = new GraphEdgeDescriptor(
                    "Product", "LINKED", "Product", 1.0, true,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_linked_edges");
            String cypher = cypherDialect.buildPathQuery(List.of(GraphHop.of(bi)));
            assertThat(cypher).contains("-[:LINKED]-(");
        }

        @Test
        @DisplayName("sum of maxRepeat exceeding 64 in Cypher mode throws IllegalArgumentException")
        void cypherHopsSumExceedingLimitThrows() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            List<GraphHop> hops = List.of(new GraphHop(edge, 1, 65));
            assertThatThrownBy(() -> cypherDialect.buildPathQuery(hops))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sum of maxRepeat equal to 64 in Cypher mode is accepted")
        void cypherHopsSumAtLimitAccepted() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            List<GraphHop> hops = List.of(new GraphHop(edge, 1, 64));
            assertThatCode(() -> cypherDialect.buildPathQuery(hops))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("empty hops list in Cypher mode throws IllegalArgumentException")
        void cypherEmptyHopsThrows() {
            List<GraphHop> empty = List.of();
            assertThatThrownBy(() -> cypherDialect.buildPathQuery(empty))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("invalid Cypher edge type throws IllegalArgumentException")
        void cypherBadEdgeTypeThrows() {
            GraphEdgeDescriptor badEdge = new GraphEdgeDescriptor(
                    "Product", "bad-name", "Product", 1.0, false,
                    GraphEdgeDescriptor.Direction.OUTGOING, "tck_linked_edges");
            List<GraphHop> hops = List.of(GraphHop.of(badEdge));
            assertThatThrownBy(() -> cypherDialect.buildPathQuery(hops))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

