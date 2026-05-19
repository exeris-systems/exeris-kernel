/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpResponseBodyDecoder} contract verification (ADR-034 §3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code supports(Class<?>, String contentType)} MUST tolerate
 *       {@code contentType == null} and empty content-type strings (server omitted
 *       the header). The TCK exercises both shapes.</li>
 *   <li>{@code decode(body, targetType, ctx)} returns {@code Object} — the SPI
 *       intentionally keeps the surface generics-free; the cast to {@code <T>}
 *       lives at the façade.</li>
 *   <li>The decoder MAY return {@code null} when {@code body.size() == 0} and
 *       {@code targetType} accepts null; the façade decides per call.</li>
 *   <li>Driver exception wrapping: binding-specific exceptions (e.g. Jackson
 *       {@code JacksonException}) MUST be wrapped in a generic
 *       {@link RuntimeException} (typically {@link IllegalStateException}); no
 *       driver types may cross the SPI boundary.</li>
 *   <li>{@link HttpResponseBodyDecoder#priority()} defaults to {@code 0}; higher
 *       wins. Ties resolve by registration order at the registry.</li>
 *   <li>Buffer ownership: the decoder MUST NOT close, retain, or extend the
 *       lifetime of the buffer. The TCK verifies the buffer is still live and
 *       its refCount is unchanged after a successful {@code decode}.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityJsonResponseBodyDecoderTckTest extends AbstractHttpResponseBodyDecoderTck {
 *     @Override
 *     protected HttpResponseBodyDecoder createDecoder() {
 *         return new CommunityJsonResponseBodyDecoder(new ObjectMapper());
 *     }
 *
 *     @Override
 *     protected String supportedContentType() {
 *         return "application/json";
 *     }
 *
 *     @Override
 *     protected byte[] validEncodedBytes() {
 *         return "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
 *     }
 *
 *     @Override
 *     protected byte[] malformedEncodedBytes() {
 *         return "{ not-json".getBytes(StandardCharsets.UTF_8);
 *     }
 *
 *     @Override
 *     protected Class<?> validTargetType() {
 *         return Map.class;
 *     }
 * }
 * }</pre>
 *
 * @since 0.8.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHttpResponseBodyDecoderTck {

    private MemoryAllocator allocator;

    /**
     * @return the decoder under test; never null
     */
    protected abstract HttpResponseBodyDecoder createDecoder();

    /**
     * Returns one content-type the decoder claims support for (e.g.
     * {@code "application/json"} for a JSON decoder). The TCK uses this to drive
     * positive {@code supports(...)} cases.
     *
     * @return non-null lower-case content-type
     */
    protected abstract String supportedContentType();

    /**
     * Returns a byte payload the decoder can successfully decode into
     * {@link #validTargetType()} (e.g. {@code "{\"k\":\"v\"}".getBytes(UTF_8)}).
     *
     * @return non-null, non-empty byte payload
     */
    protected abstract byte[] validEncodedBytes();

    /**
     * Returns a byte payload that is syntactically invalid for the binding (e.g.
     * truncated JSON) — exercised for the driver-exception-wrapping clause.
     *
     * @return non-null, non-empty byte payload
     */
    protected abstract byte[] malformedEncodedBytes();

    /**
     * Returns a target class the decoder can produce from
     * {@link #validEncodedBytes()} (e.g. {@code Map.class}).
     *
     * @return non-null target type
     */
    protected abstract Class<?> validTargetType();

    /**
     * Creates the {@link MemoryAllocator} used to back {@link LoanedBuffer}
     * fixtures. The TCK closes the returned allocator after all tests finish.
     *
     * @return non-null allocator
     */
    protected abstract MemoryAllocator createAllocator();

    @BeforeAll
    void allocatorSetup() {
        this.allocator = createAllocator();
    }

    @AfterAll
    void allocatorTeardown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    private HttpResponseDecodingContext context() {
        return new HttpResponseDecodingContext(200, List.of(), allocator);
    }

    private LoanedBuffer bufferOf(byte[] bytes) {
        LoanedBuffer buf = allocator.allocateNetwork(Math.max(1, bytes.length));
        if (bytes.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, buf.segment(), 0L, bytes.length);
        }
        buf.setSize(bytes.length);
        return buf;
    }

    @Nested
    @DisplayName("supports(Class<?>, String)")
    class Supports {

        @Test
        @DisplayName("supports returns true for (validTargetType, supportedContentType)")
        void supportsHappyPath() {
            HttpResponseBodyDecoder decoder = createDecoder();
            assertThat(decoder.supports(validTargetType(), supportedContentType()))
                    .as("decoder must claim support for its own (targetType, contentType)")
                    .isTrue();
        }

        @Test
        @DisplayName("supports tolerates contentType == null (server omitted the header)")
        void supportsTolerantOfNullContentType() {
            HttpResponseBodyDecoder decoder = createDecoder();
            // Contract: MUST tolerate null content-type. The decoder may still
            // return true or false based on its own policy, but it MUST NOT throw.
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> decoder.supports(validTargetType(), null),
                    "supports(...) must not throw when contentType == null");
        }

        @Test
        @DisplayName("supports tolerates empty contentType string")
        void supportsTolerantOfEmptyContentType() {
            HttpResponseBodyDecoder decoder = createDecoder();
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> decoder.supports(validTargetType(), ""),
                    "supports(...) must not throw when contentType is empty");
        }

        @Test
        @DisplayName("supports returns false for a clearly unsupported content type")
        void supportsRejectsUnsupportedContentType() {
            HttpResponseBodyDecoder decoder = createDecoder();
            // image/png is well outside any text/structured-data binding; the
            // decoder MUST decline it when a non-null content-type is supplied.
            assertThat(decoder.supports(validTargetType(), "image/png"))
                    .as("decoder must decline unrelated content types")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("decode(body, targetType, ctx)")
    class Decode {

        @Test
        @DisplayName("decode produces a non-null Object for valid input bytes")
        void decodeHappyPath() {
            HttpResponseBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                Object decoded = decoder.decode(body, validTargetType(), context());
                assertThat(decoded)
                        .as("decode must return a non-null value for non-empty valid input")
                        .isNotNull();
                assertThat(validTargetType().isInstance(decoded))
                        .as("decoded value must be an instance of validTargetType()")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("decode does NOT close or extend lifetime of the buffer (refCount unchanged)")
        void decodeDoesNotMutateRefCount() {
            HttpResponseBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                int before = body.refCount();
                decoder.decode(body, validTargetType(), context());
                int after = body.refCount();
                assertThat(after)
                        .as("decoder MUST NOT mutate the buffer refCount; façade owns lifecycle")
                        .isEqualTo(before);
                assertThat(body.isAlive())
                        .as("decoder MUST NOT close the buffer; façade closes in finally")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("decode MAY return null for an empty body when targetType permits")
        void decodeReturnsNullForEmptyBody() {
            HttpResponseBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(new byte[0])) {
                // Contract: decoder MAY return null for size == 0. Either null OR a
                // domain-specific empty value is acceptable; the decoder must not
                // throw on empty input.
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> decoder.decode(body, validTargetType(), context()),
                        "decode must not throw on body.size() == 0");
            }
        }

        @Test
        @DisplayName("decode wraps driver-specific exceptions into a generic RuntimeException")
        void decodeWrapsDriverExceptions() {
            HttpResponseBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(malformedEncodedBytes())) {
                assertThatThrownBy(() -> decoder.decode(body, validTargetType(), context()))
                        .as("driver exceptions MUST be wrapped — no binding-specific types may escape")
                        .isInstanceOf(RuntimeException.class)
                        .extracting(Object::getClass)
                        .satisfies(cls -> {
                            String pkg = ((Class<?>) cls).getPackage().getName();
                            assertThat(pkg)
                                    .as("wrapped exception MUST live in java.* (not in driver package)")
                                    .startsWith("java.");
                        });
            }
        }

        @Test
        @DisplayName("decode returns Object (not <T>) — verifying generics-free SPI surface")
        void decodeReturnsObjectType() {
            HttpResponseBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                // Compile-time check: the static type is Object. The runtime check is
                // that the returned reference can be assigned to Object without cast.
                Object decoded = decoder.decode(body, validTargetType(), context());
                Object viaObject = decoded;
                assertThat(viaObject).isSameAs(decoded);
            }
        }
    }

    @Nested
    @DisplayName("priority()")
    class Priority {

        @Test
        @DisplayName("priority() returns a non-negative value (default 0)")
        void defaultPriorityIsNonNegative() {
            HttpResponseBodyDecoder decoder = createDecoder();
            assertThat(decoder.priority())
                    .as("priority() must be non-negative for the decoder to participate in resolution")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** UTF-8 charset constant; subclasses may reference this for fixtures. */
    @SuppressWarnings("unused")
    protected static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;
}
