/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.tck.contract.http.AbstractHttpHandlerTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: HttpHandler TCK")
class CoreHttpHandlerTckTest extends AbstractHttpHandlerTck {

    @Override
    protected HttpExchange createRecordingExchange(HttpRequest request) {
        return new CoreHttpProviderFixture.CoreHttpExchangeFixture(request);
    }
}
