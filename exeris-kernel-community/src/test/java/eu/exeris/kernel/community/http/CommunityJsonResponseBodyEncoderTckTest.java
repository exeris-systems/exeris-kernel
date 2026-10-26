/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpResponseBodyEncoderTck;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@DisplayName("Community: HttpResponseBodyEncoder TCK (Jackson 3)")
class CommunityJsonResponseBodyEncoderTckTest extends AbstractHttpResponseBodyEncoderTck {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected HttpResponseBodyEncoder createEncoder() {
        return new JsonBodyEncoder(MAPPER);
    }

    @Override
    protected Object samplePayload() {
        return Map.of("k", "v");
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @Override
    protected Optional<Object> failingPayload() {
        return Optional.of(new Exploding());
    }

    @Override
    protected Optional<String> expectedContentTypePrefix() {
        return Optional.of("application/json");
    }

    @Override
    protected Optional<byte[]> expectedSampleBody() {
        return Optional.of("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
    }

    /** A bean whose only property throws during serialization, forcing a mid-write failure. */
    public static final class Exploding {

        /**
         * Always throws, so serializing this bean fails.
         *
         * @return never returns
         */
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }
}
