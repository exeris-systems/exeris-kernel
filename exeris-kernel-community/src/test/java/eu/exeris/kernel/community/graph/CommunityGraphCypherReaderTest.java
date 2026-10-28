/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityGraphCypherReader} error mapping for path traversal.
 *
 * @since 0.13
 */
@DisplayName("L1 Unit: CommunityGraphCypherReader path traversal errors")
class CommunityGraphCypherReaderTest {

    CommunityGraphCypherReaderTest() {
        super();
    }

    @Test
    @DisplayName("traversePath with an invalid relationship type surfaces as a PATH GraphQueryException")
    void traversePathInvalidRelationshipTypeSurfacesAsPathQueryException() {
        CypherExecutor executor = new CypherExecutor() {
            @Override
            public <T> T executeRead(String query, Map<String, Object> params,
                                     Function<org.neo4j.driver.Result, T> reader) {
                throw new AssertionError("the driver must not be reached for an invalid identifier");
            }

            @Override
            public void executeWrite(String query, Map<String, Object> params) {
                throw new AssertionError("a traversal must not write");
            }
        };
        CommunityGraphCypherReader reader =
                new CommunityGraphCypherReader(new CommunityGraphDialect("g", "neo4j"), executor);
        GraphEdgeDescriptor bad = new GraphEdgeDescriptor("Product", "bad-name", "Product", 1.0, false,
                GraphEdgeDescriptor.Direction.OUTGOING, "tck_linked_edges");
        GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(), List.of(GraphHop.of(bad)));

        assertThatThrownBy(() -> reader.traversePath(path))
                .isInstanceOf(GraphQueryException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH"));
    }
}
