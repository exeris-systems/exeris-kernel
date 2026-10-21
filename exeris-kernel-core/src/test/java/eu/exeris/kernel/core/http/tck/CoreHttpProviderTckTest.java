/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.tck.contract.http.AbstractHttpProviderTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

@DisplayName("Core: HttpProvider TCK")
class CoreHttpProviderTckTest extends AbstractHttpProviderTck {

    @Override
    protected HttpProvider createProvider() {
        return new CoreHttpProviderFixture();
    }

    @Override
    protected HttpProvider selectAmong(List<HttpProvider> candidates) {
        // The kernel's provider selection routine is BootstrapProviderSelector.
        return BootstrapProviderSelector.selectHighestPriority(candidates, HttpProvider::priority).orElseThrow();
    }
}
