/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.tck.contract.http.AbstractHttpServerEngineTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: HttpServerEngine TCK")
class CoreHttpServerEngineTckTest extends AbstractHttpServerEngineTck {

    @Override
    protected HttpServerEngine createEngine(HttpConfig config) {
        return new CoreHttpProviderFixture().createServerEngine(config);
    }
}
