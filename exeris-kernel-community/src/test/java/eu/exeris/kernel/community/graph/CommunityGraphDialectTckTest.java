/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphDialectTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractGraphDialectTck} backed by
 * {@link CommunityGraphDialect}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>Dialect generates non-blank SQL/PGQ query strings for all DSL operations</li>
 *   <li>Multi-hop depth bounds are reflected in output</li>
 *   <li>DDL generation is idempotent</li>
 *   <li>dialectName() is stable and non-blank</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityGraphDialect TCK")
class CommunityGraphDialectTckTest extends AbstractGraphDialectTck {

    @Override
    protected GraphDialect createDialect() {
        return new CommunityGraphDialect("tck_graph");
    }
}
