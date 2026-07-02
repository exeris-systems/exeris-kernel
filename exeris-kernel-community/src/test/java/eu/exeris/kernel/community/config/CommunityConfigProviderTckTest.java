/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.config;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractConfigProviderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractConfigProviderTck} backed by
 * {@link CommunityConfigProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>KernelSettings is computed exactly once (LazyConstant / JEP 526 semantic)</li>
 *   <li>Concurrent VT access under contention still yields a single instance</li>
 *   <li>priority() == 0 — correct Open-Core slot</li>
 *   <li>No banned Jackson/SnakeYAML parsers pulled in by this implementation</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityConfigProvider TCK")
class CommunityConfigProviderTckTest extends AbstractConfigProviderTck {

    @Override
    protected ConfigProvider createProvider() {
        return new CommunityConfigProvider();
    }
}

