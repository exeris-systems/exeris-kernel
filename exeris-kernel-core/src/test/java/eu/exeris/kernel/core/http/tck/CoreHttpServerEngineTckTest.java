/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
