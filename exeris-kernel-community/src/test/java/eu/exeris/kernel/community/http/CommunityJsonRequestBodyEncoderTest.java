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
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestEncodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract for {@link CommunityJsonRequestBodyEncoder} streaming: byte-for-byte equivalence to
 * {@code writeValueAsBytes}, the buffer-grow path, and zero outstanding off-heap loans on both success
 * and failure (ADR-043 ownership). The {@link AbstractHttpRequestBodyEncoderTck} binding covers the SPI
 * envelope/content-type/header-invariance contract; this fills the streaming-specific gaps it does not.
 */
final class CommunityJsonRequestBodyEncoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CommunityJsonRequestBodyEncoder encoder = new CommunityJsonRequestBodyEncoder(mapper);

    private MemoryAllocator realAllocator;
    private LeakTrackingAllocator allocator;

    @BeforeEach
    void setUp() {
        realAllocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        allocator = new LeakTrackingAllocator(realAllocator);
    }

    @AfterEach
    void tearDown() {
        realAllocator.close();
    }

    private HttpRequestEncodingContext context() {
        return new HttpRequestEncodingContext(HttpMethod.POST, "/widgets", allocator);
    }

    private static byte[] readBack(HttpEncodedBody encoded) {
        LoanedBuffer body = encoded.body();
        int size = Math.toIntExact(body.size());
        byte[] out = new byte[size];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(out), 0L, size);
        return out;
    }

    @Test
    @DisplayName("small payload: bytes identical to writeValueAsBytes, JSON content-type, zero leak after close")
    void encodesSmallPayloadIdenticalToWriteValueAsBytes() {
        Object payload = Map.of("id", 7, "name", "grace");
        byte[] expected = mapper.writeValueAsBytes(payload);

        HttpEncodedBody encoded = encoder.encode(payload, context());

        assertArrayEquals(expected, readBack(encoded));
        assertEquals(List.of(new HttpHeader("content-type", "application/json")), encoded.headers());
        assertEquals(1L, allocator.outstanding(), "body must own exactly one live buffer");
        encoded.body().close();
        assertEquals(0L, allocator.outstanding(), "closing the body must release the loan");
    }

    @Test
    @DisplayName("large payload (> initial estimate): grow path yields identical bytes and one net live buffer")
    void encodesLargePayloadForcingGrowPath() {
        List<String> payload = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "element-" + i + "-" + "x".repeat(100))
                .toList();
        byte[] expected = mapper.writeValueAsBytes(payload);
        assertTrue(expected.length > 4096, "test payload must exceed the initial buffer to exercise grow");

        HttpEncodedBody encoded = encoder.encode(payload, context());

        assertArrayEquals(expected, readBack(encoded));
        assertEquals(1L, allocator.outstanding(), "grow must close superseded buffers — one net live buffer");
        encoded.body().close();
        assertEquals(0L, allocator.outstanding());
    }

    @Test
    @DisplayName("serialization failure releases the loan (no leak)")
    void noLeakWhenSerializationFails() {
        assertThrows(IllegalStateException.class, () -> encoder.encode(new Exploding(), context()));
        assertEquals(0L, allocator.outstanding(), "failed serialization must not leak the loaned buffer");
    }

    @Test
    @DisplayName("failure AFTER the buffer has grown releases the grown loan (no leak)")
    void noLeakWhenFailureOccursAfterGrow() {
        List<Object> payload = List.of("x".repeat(5000), new Exploding());

        assertThrows(IllegalStateException.class, () -> encoder.encode(payload, context()));
        assertEquals(0L, allocator.outstanding(),
                "the grown buffer (not just the initial one) must be released on failure");
    }

    /** A bean whose only property throws during serialization, forcing a mid-write Jackson failure. */
    public static final class Exploding {
        @SuppressWarnings("PMD.UnusedMethodParameter")
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }
}
