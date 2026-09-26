/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphDialectTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractGraphDialectTck} backed by
 * {@link CommunityGraphDialect} in Cypher (Neo4j) mode.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>Cypher dialect generates non-blank query strings for all DSL operations</li>
 *   <li>Multi-hop depth bounds are reflected in variable-length MATCH output</li>
 *   <li>DDL generation returns comment strings (Cypher schema is schema-less)</li>
 *   <li>dialectName() returns "Cypher" — stable identifier</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityGraphDialect (Cypher) TCK")
class CommunityGraphCypherDialectTckTest extends AbstractGraphDialectTck {

    @Override
    protected GraphDialect createDialect() {
        return new CommunityGraphDialect("tck_graph", "neo4j");
    }
}
