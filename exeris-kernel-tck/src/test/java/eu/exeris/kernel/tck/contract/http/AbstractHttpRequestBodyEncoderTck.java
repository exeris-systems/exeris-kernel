/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestEncodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpRequestBodyEncoder} contract verification (ADR-034 §3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code supports(Class<?>)} accepts a {@code Class<?>} argument and answers
 *       whether this encoder handles that payload type. The SPI Javadoc declares the
 *       parameter "never null"; well-behaved drivers typically return {@code false}
 *       (rather than throwing) for {@code null} — implementations vary, both shapes
 *       are contract-conformant and the TCK exercises only the documented non-null
 *       path.</li>
 *   <li>{@code encode(payload, ctx)} returns a non-null {@link HttpEncodedBody}
 *       whose {@code headers()} is non-null (may be empty) and whose {@code body()}
 *       is either a non-null {@link LoanedBuffer} or {@code null} for bodyless
 *       encodings.</li>
 *   <li>For any non-bodyless encoding the encoder MUST emit a
 *       {@code content-type} header in {@link HttpEncodedBody#headers()}.</li>
 *   <li>Encoders MUST NOT depend on request headers — the
 *       {@link HttpRequestEncodingContext} deliberately carries only
 *       {@code (method, path, allocator)} because the encoder runs before header
 *       finalisation. The TCK exercises this by re-encoding the same payload under
 *       different methods/paths and asserting the body bytes are identical.</li>
 *   <li>{@link HttpRequestBodyEncoder#priority()} defaults to {@code 0}; higher
 *       wins at registry resolution.</li>
 *   <li>Driver exception wrapping: binding-specific exceptions (e.g. Jackson)
 *       MUST be wrapped in a generic {@link RuntimeException} (typically
 *       {@link IllegalStateException}); no driver types may cross the SPI boundary.</li>
 *   <li>Buffer ownership: the returned {@link LoanedBuffer} is owned by the
 *       caller (the façade transfers ownership to {@link eu.exeris.kernel.spi.http.HttpRequest}
 *       on construction). The TCK verifies the returned buffer is live
 *       ({@code refCount() > 0}) and closes it itself.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityJsonRequestBodyEncoderTckTest extends AbstractHttpRequestBodyEncoderTck {
 *     @Override
 *     protected HttpRequestBodyEncoder createEncoder() {
 *         return new CommunityJsonRequestBodyEncoder(new ObjectMapper());
 *     }
 *
 *     @Override
 *     protected Object samplePayload() {
 *         return Map.of("k", "v");
 *     }
 *
 *     @Override
 *     protected String expectedContentTypePrefix() {
 *         return "application/json";
 *     }
 * }
 * }</pre>
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>This TCK imports only from {@code exeris-kernel-spi}. Drivers may not leak
 * their binding library types onto the TCK surface; the subclass picks a payload
 * that the encoder under test can serialise.
 *
 * @since 0.8.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHttpRequestBodyEncoderTck {

    private MemoryAllocator allocator;

    /**
     * Creates the encoder under test. Implementations may return a fresh instance
     * per call; the TCK does not assume reuse across tests.
     *
     * @return non-null encoder
     */
    protected abstract HttpRequestBodyEncoder createEncoder();

    /**
     * Returns a non-null, encoder-supported sample payload (e.g. a {@code Map} for
     * a JSON encoder, a domain record for a Protobuf encoder).
     *
     * @return non-null payload
     */
    protected abstract Object samplePayload();

    /**
     * Lower-case media-type prefix the encoder MUST emit on a non-bodyless
     * encoding (e.g. {@code "application/json"}). The TCK checks
     * {@code content-type} header value begins with this string ignoring case.
     *
     * @return non-null lower-case media-type prefix
     */
    protected abstract String expectedContentTypePrefix();

    /**
     * Creates the {@link MemoryAllocator} used to back the encoding context. The
     * TCK closes the returned allocator after all tests finish.
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

    private HttpRequestEncodingContext context() {
        return new HttpRequestEncodingContext(HttpMethod.POST, "/tck", allocator);
    }

    private HttpRequestEncodingContext context(HttpMethod method, String path) {
        return new HttpRequestEncodingContext(method, path, allocator);
    }

    @Nested
    @DisplayName("supports(Class<?>)")
    class Supports {

        @Test
        @DisplayName("supports returns true for the encoder's sample payload type")
        void supportsSamplePayloadType() {
            HttpRequestBodyEncoder encoder = createEncoder();
            assertThat(encoder.supports(samplePayload().getClass()))
                    .as("supports(samplePayload().getClass())")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("encode(payload, ctx)")
    class Encode {

        @Test
        @DisplayName("encode returns a non-null HttpEncodedBody with non-null headers")
        void encodeReturnsNonNullEnvelope() {
            HttpRequestBodyEncoder encoder = createEncoder();
            HttpEncodedBody encoded = encoder.encode(samplePayload(), context());
            try {
                assertThat(encoded).isNotNull();
                assertThat(encoded.headers()).isNotNull();
            } finally {
                closeQuietly(encoded);
            }
        }

        @Test
        @DisplayName("encode produces a non-empty live LoanedBuffer for a non-bodyless payload")
        void encodeProducesLiveBuffer() {
            HttpRequestBodyEncoder encoder = createEncoder();
            HttpEncodedBody encoded = encoder.encode(samplePayload(), context());
            try {
                assertThat(encoded.body())
                        .as("non-bodyless encoding must return a non-null buffer")
                        .isNotNull();
                assertThat(encoded.body().size())
                        .as("encoded buffer must carry at least one byte for a non-empty payload")
                        .isGreaterThan(0L);
                assertThat(encoded.body().refCount())
                        .as("returned buffer must be live (refCount > 0); ownership transfers to caller")
                        .isGreaterThan(0);
            } finally {
                closeQuietly(encoded);
            }
        }

        @Test
        @DisplayName("encode sets content-type header for any non-bodyless encoding")
        void encodeSetsContentType() {
            HttpRequestBodyEncoder encoder = createEncoder();
            HttpEncodedBody encoded = encoder.encode(samplePayload(), context());
            try {
                String prefix = expectedContentTypePrefix();
                HttpHeader contentType = encoded.headers().stream()
                        .filter(h -> h.nameEqualsIgnoreCase("content-type"))
                        .findFirst()
                        .orElse(null);
                assertThat(contentType)
                        .as("non-bodyless encoding MUST include a content-type header")
                        .isNotNull();
                assertThat(contentType.value().toLowerCase())
                        .as("content-type must start with %s", prefix)
                        .startsWith(prefix.toLowerCase());
            } finally {
                closeQuietly(encoded);
            }
        }

        @Test
        @DisplayName("encode does NOT depend on request headers — different (method, path) yields identical body bytes")
        void encodeIsHeaderLocked() {
            HttpRequestBodyEncoder encoder = createEncoder();
            HttpEncodedBody a = encoder.encode(samplePayload(), context(HttpMethod.POST, "/a"));
            HttpEncodedBody b = encoder.encode(samplePayload(), context(HttpMethod.PUT,  "/different/path?x=1"));
            try {
                byte[] aBytes = readAll(a.body());
                byte[] bBytes = readAll(b.body());
                assertThat(aBytes)
                        .as("encoder must be context-locked: body bytes invariant under method/path changes")
                        .containsExactly(bBytes);
            } finally {
                closeQuietly(a);
                closeQuietly(b);
            }
        }

        @Test
        @DisplayName("encode rejects null payload (encoder contract: registry filters non-supports cases)")
        void encodeRejectsNullPayload() {
            HttpRequestBodyEncoder encoder = createEncoder();
            // SPI Javadoc says "payload object; non-null". Standard drivers typically
            // throw NullPointerException; any RuntimeException is contract-conformant.
            assertThatThrownBy(() -> encoder.encode(null, context()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("encode rejects null context")
        void encodeRejectsNullContext() {
            HttpRequestBodyEncoder encoder = createEncoder();
            assertThatThrownBy(() -> encoder.encode(samplePayload(), null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("priority()")
    class Priority {

        @Test
        @DisplayName("priority() default returns 0 unless the implementation overrides it")
        void defaultPriorityIsZeroOrPositive() {
            HttpRequestBodyEncoder encoder = createEncoder();
            // Default SPI value is 0; drivers may declare higher to win ties.
            // The contract is "higher wins"; we assert non-negative so accidental
            // negative priorities (which would never resolve) are flagged.
            assertThat(encoder.priority())
                    .as("priority() must be non-negative for the encoder to participate in resolution")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Driver exception wrapping")
    class ExceptionWrapping {

        @Test
        @DisplayName("encode does not throw any *checked* exception type — SPI signature forbids it")
        void noCheckedExceptions() {
            HttpRequestBodyEncoder encoder = createEncoder();
            assertThatCode(() -> encoder.encode(samplePayload(), context()))
                    .as("happy-path encode must not throw a checked exception")
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void closeQuietly(HttpEncodedBody encoded) {
        if (encoded != null && encoded.body() != null) {
            try {
                encoded.body().close();
            } catch (RuntimeException ignored) {
                // tests must not fail in teardown
            }
        }
    }

    private static byte[] readAll(LoanedBuffer buf) {
        long size = buf.size();
        byte[] out = new byte[Math.toIntExact(size)];
        java.lang.foreign.MemorySegment.copy(
                buf.segment(), 0L,
                java.lang.foreign.MemorySegment.ofArray(out), 0L,
                size);
        return out;
    }
}
