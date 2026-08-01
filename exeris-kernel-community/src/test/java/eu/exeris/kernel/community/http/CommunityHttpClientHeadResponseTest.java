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
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A response to {@code HEAD} declares the length of a body it will never send.
 *
 * <p>RFC 9110 §6.4.1. Before this, the client decoder read {@code Content-Length} as a promise of bytes
 * to come, so every {@code HEAD} ended as a truncation failure and the method was unusable — which is
 * what the S3 blob driver found when it tried to {@code stat} an object without downloading it.
 *
 * <p>Framing is decided by the request method, which the response cannot carry, so the flag is threaded
 * from the engine rather than inferred here.
 *
 * @since 0.11.0
 */
@DisplayName("Community HTTP client: a HEAD response has no body to wait for")
class CommunityHttpClientHeadResponseTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final String HEAD_RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nContent-Type: image/png\r\n\r\n";

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("decodes to headers only, keeping the declared length visible to the caller")
    void headResponseDecodesWithoutBody() {
        try (LoanedBuffer aggregate = load(HEAD_RESPONSE)) {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    ALLOCATOR, aggregate, aggregate.size(), HttpVersion.HTTP_1_1, true);

            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.hasBody())
                    .as("the declared length describes the object, not the bytes on this response")
                    .isFalse();
            assertThat(response.headers())
                    .as("a stat that lost Content-Length would have to fall back to downloading")
                    .anySatisfy(header -> {
                        assertThat(header.nameEqualsIgnoreCase("Content-Length")).isTrue();
                        assertThat(header.value()).isEqualTo("4096");
                    });
        }
    }

    @Test
    @DisplayName("the same bytes read as a bodied response still fail, so the flag is load-bearing")
    void withoutTheFlagItIsStillATruncation() {
        try (LoanedBuffer aggregate = load(HEAD_RESPONSE)) {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    ALLOCATOR, aggregate, aggregate.size(), HttpVersion.HTTP_1_1, false))
                    .as("if this passed too, the flag would not be what makes HEAD work")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Truncated HTTP response body");
        }
    }

    @Test
    @DisplayName("completion is decided at the header terminator, not at Content-Length")
    void expectedTotalStopsAtHeaders() {
        try (LoanedBuffer aggregate = load(HEAD_RESPONSE)) {
            long terminator = CommunityHttpClientResponseDecoder.resolveHeaderTerminator(
                    -1, aggregate.segment(), aggregate.size());

            long bodyless = CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                    -1, aggregate.segment(), aggregate.size(), terminator, true);
            long bodied = CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                    -1, aggregate.segment(), aggregate.size(), terminator, false);

            assertThat(bodyless)
                    .as("waiting for 4096 bytes that never arrive would block the read loop until the "
                            + "peer closed the connection")
                    .isEqualTo(aggregate.size());
            assertThat(bodied).isEqualTo(aggregate.size() + 4096);
        }
    }

    private static LoanedBuffer load(String wire) {
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);
        LoanedBuffer buffer = ALLOCATOR.allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, buffer.segment(), 0L, bytes.length);
        buffer.setSize(bytes.length);
        return buffer;
    }
}
