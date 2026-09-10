/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommunityTextResponseBodyDecoder} — raw UTF-8 passthrough for
 * {@code String.class}, the primitive the JWKS-over-HTTP fetcher relies on.
 */
@DisplayName("CommunityTextResponseBodyDecoder — raw UTF-8 String passthrough")
class CommunityTextResponseBodyDecoderTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final CommunityTextResponseBodyDecoder decoder = new CommunityTextResponseBodyDecoder();

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    private static HttpResponseDecodingContext ctx() {
        return new HttpResponseDecodingContext(200, List.<HttpHeader>of(), ALLOCATOR);
    }

    @Test
    @DisplayName("supports String.class for any (incl. absent) content type, nothing else")
    void supportsStringOnly() {
        assertThat(decoder.supports(String.class, "application/json")).isTrue();
        assertThat(decoder.supports(String.class, null)).isTrue();
        assertThat(decoder.supports(String.class, "text/plain")).isTrue();
        assertThat(decoder.supports(Map.class, "application/json")).isFalse();
    }

    @Test
    @DisplayName("decodes a JWKS-shaped body verbatim as UTF-8 — no object mapping")
    void decodesRawText() {
        String json = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\"}]}";
        try (LoanedBuffer body = TestJwt.bufferOf(json)) {
            assertThat(decoder.decode(body, String.class, ctx())).isEqualTo(json);
        }
    }

    @Test
    @DisplayName("an empty body decodes to null per the decoder contract")
    void emptyBodyIsNull() {
        try (LoanedBuffer body = TestJwt.bufferOf("")) {
            assertThat(decoder.decode(body, String.class, ctx())).isNull();
        }
    }
}
