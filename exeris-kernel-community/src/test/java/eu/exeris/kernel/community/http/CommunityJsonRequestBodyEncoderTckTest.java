/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpRequestBodyEncoderTck;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@DisplayName("Community: HttpRequestBodyEncoder TCK (Jackson 3)")
class CommunityJsonRequestBodyEncoderTckTest extends AbstractHttpRequestBodyEncoderTck {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected HttpRequestBodyEncoder createEncoder() {
        return new CommunityJsonRequestBodyEncoder(MAPPER);
    }

    @Override
    protected Object samplePayload() {
        return Map.of("k", "v");
    }

    @Override
    protected String expectedContentTypePrefix() {
        return "application/json";
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
