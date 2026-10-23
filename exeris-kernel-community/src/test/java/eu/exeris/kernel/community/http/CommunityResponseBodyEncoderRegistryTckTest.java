/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.tck.contract.http.AbstractHttpResponseBodyEncoderRegistryTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

@DisplayName("Community: HttpResponseBodyEncoderRegistry TCK")
class CommunityResponseBodyEncoderRegistryTckTest extends AbstractHttpResponseBodyEncoderRegistryTck {

    @Override
    protected HttpResponseBodyEncoderRegistry createRegistry() {
        return new CommunityHttpProvider().responseBodyEncoderRegistry();
    }

    @Override
    protected Class<?> supportedPayloadType() {
        return Map.class;
    }
}
