/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphEngineTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractGraphEngineTck} backed by
 * {@link CommunityGraphEngine}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>openSession() returns a non-null session when running</li>
 *   <li>Node and edge registry is correct and immutable (defensive copy)</li>
 *   <li>Concurrent VT registration is safe (ArrayList protected by concurrent access contract)</li>
 *   <li>isRunning() is correctly maintained through the lifecycle</li>
 *   <li>engineName() is "Community/JdbcGraph" — stable identifier for SRE tools</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityGraphEngine TCK")
class CommunityGraphEngineTckTest extends AbstractGraphEngineTck {

    @Override
    protected GraphEngine createEngine() {
        return new CommunityGraphProvider()
                .createEngine(GraphConfig.create("test-graph"));
    }
}

