/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpClientEngineTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: HttpClientEngine TCK")
class CoreHttpClientEngineTckTest extends AbstractHttpClientEngineTck {

    @Override
    protected HttpClientEngine createEngine(HttpConfig config) {
        return new CoreHttpProviderFixture().createClientEngine(config);
    }
}
