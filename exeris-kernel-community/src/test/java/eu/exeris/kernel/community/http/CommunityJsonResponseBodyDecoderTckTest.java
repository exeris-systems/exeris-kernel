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
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpResponseBodyDecoderTck;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@DisplayName("Community: HttpResponseBodyDecoder TCK (Jackson 3)")
class CommunityJsonResponseBodyDecoderTckTest extends AbstractHttpResponseBodyDecoderTck {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected HttpResponseBodyDecoder createDecoder() {
        return new CommunityJsonResponseBodyDecoder(MAPPER);
    }

    @Override
    protected String supportedContentType() {
        return "application/json";
    }

    @Override
    protected byte[] validEncodedBytes() {
        return "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] malformedEncodedBytes() {
        // Truncated JSON object — Jackson surfaces JacksonException; driver MUST wrap.
        return "{ \"k\": ".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected Class<?> validTargetType() {
        return Map.class;
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
