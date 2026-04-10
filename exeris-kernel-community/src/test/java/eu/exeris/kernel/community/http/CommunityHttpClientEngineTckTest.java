/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpClientEngineTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: HttpClientEngine TCK")
class CommunityHttpClientEngineTckTest extends AbstractHttpClientEngineTck {

    @Override
    protected HttpClientEngine createEngine(HttpConfig config) {
        return new CommunityHttpClientEngine(config);
    }
}
