/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphProviderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractGraphProviderTck} backed by
 * {@link CommunityGraphProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>providerId() and providerName() are stable, non-blank identifiers</li>
 *   <li>priority() == 0 — correct Open-Core slot (Enterprise wins with higher priority)</li>
 *   <li>ServiceLoader discovery selects at least one GraphProvider from the classpath</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityGraphProvider TCK")
class CommunityGraphProviderTckTest extends AbstractGraphProviderTck {

    @Override
    protected GraphProvider createProvider() {
        return new CommunityGraphProvider();
    }
}

