/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.tck.contract.http.AbstractHttpProviderTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: HttpProvider TCK")
class CoreHttpProviderTckTest extends AbstractHttpProviderTck {

    @Override
    protected HttpProvider createProvider() {
        return new CoreHttpProviderFixture();
    }
}
