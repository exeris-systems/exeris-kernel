/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link HttpResponseBodyEncoder} contract verification (ADR-034 §3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code supports(Class<?>)} answers whether the encoder can encode the given payload type:
 *       {@code true} for the type of the binding's sample payload and, when the binding declares
 *       one, {@code false} for a type it cannot encode.</li>
 *   <li>{@code encode(payload, ctx)} returns a non-null {@link HttpEncodedBody} with a non-null
 *       header list. A payload that encodes to bytes carries a live {@link LoanedBuffer}
 *       ({@code refCount() > 0}) whose {@code size()} is the number of encoded bytes; one that
 *       encodes to nothing answers {@link HttpEncodedBody#noBody()}.</li>
 *   <li>A {@code null} payload is a legal input: the encoder answers with a non-null carrier and
 *       does not throw.</li>
 *   <li>The body is allocated through {@link HttpResponseEncodingContext#allocator()}: one live
 *       buffer per encode, and releasing it returns the loan to that allocator.</li>
 *   <li>The buffer belongs to the caller once returned. The encoder neither closes it nor retains
 *       it: two encodes return two independent live buffers, and the first stays readable and
 *       unchanged after the second.</li>
 *   <li>An encode that fails releases every buffer it allocated; the allocator has no outstanding
 *       loan afterwards.</li>
 *   <li>When the binding declares the media type its encoding establishes, the
 *       {@code content-type} header in {@link HttpEncodedBody#headers()} carries it.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityJsonResponseBodyEncoderTckTest extends AbstractHttpResponseBodyEncoderTck {
 *     @Override
 *     protected HttpResponseBodyEncoder createEncoder() {
 *         return new JsonBodyEncoder(new ObjectMapper());
 *     }
 *
 *     @Override
 *     protected Object samplePayload() {
 *         return Map.of("k", "v");
 *     }
 *
 *     @Override
 *     protected MemoryAllocator createAllocator() {
 *         return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
 *     }
 * }
 * }
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>This TCK imports only from {@code exeris-kernel-spi}. The binding picks payloads that the
 * encoder under test can serialise and supplies any binding-library type through those payloads.
 *
 * @since 0.13
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHttpResponseBodyEncoderTck {

    private MemoryAllocator baseAllocator;
    private TrackingAllocator allocator;

    /**
     * Creates the contract; subclasses supply the encoder under test via {@link #createEncoder()}.
     */
    public AbstractHttpResponseBodyEncoderTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the encoder under test. Implementations may return a fresh instance per call; the
     * TCK does not assume reuse across tests.
     *
     * @return non-null encoder
     */
    protected abstract HttpResponseBodyEncoder createEncoder();

    /**
     * Returns a non-null payload the encoder supports and encodes to at least one byte.
     *
     * @return non-null payload
     */
    protected abstract Object samplePayload();

    /**
     * Creates the {@link MemoryAllocator} the encoding context is backed by. The TCK wraps it to
     * count loans and closes it after all tests finish.
     *
     * @return non-null allocator
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Returns a payload whose encoding fails, so the failure path can be judged, or empty when the
     * encoder has no payload it fails on. The failing case is skipped, not passed, when empty.
     *
     * @return a payload that makes {@code encode} throw, or empty
     */
    protected Optional<Object> failingPayload() {
        return Optional.empty();
    }

    /**
     * Returns a payload type the encoder declines in {@code supports}, or empty when it accepts
     * every type. When present, {@code supports} must answer {@code false} for it.
     *
     * @return a type the encoder does not support, or empty
     */
    protected Optional<Class<?>> unsupportedPayloadType() {
        return Optional.empty();
    }

    /**
     * Returns the lower-case media-type prefix the encoder establishes in {@code content-type}
     * for {@link #samplePayload()}, or empty when the binding does not declare one. When present
     * the header must exist and start with it, ignoring case; when empty the case is skipped,
     * not passed.
     *
     * @return media-type prefix, or empty
     */
    protected Optional<String> expectedContentTypePrefix() {
        return Optional.empty();
    }

    /**
     * Returns the exact bytes {@link #samplePayload()} encodes to, or empty when the binding does
     * not pin them. When present the body's {@code size()} must equal its length and its bytes
     * must match; when empty the case is skipped, not passed.
     *
     * @return expected encoded bytes, or empty
     */
    protected Optional<byte[]> expectedSampleBody() {
        return Optional.empty();
    }

    @BeforeAll
    void allocatorSetup() {
        this.baseAllocator = createAllocator();
    }

    @AfterAll
    void allocatorTeardown() {
        if (baseAllocator != null) {
            baseAllocator.close();
        }
    }

    @BeforeEach
    void freshTracker() {
        this.allocator = new TrackingAllocator(baseAllocator);
    }

    private HttpResponseEncodingContext context() {
        HttpRequest request = new HttpRequest(HttpMethod.GET, "/tck", HttpVersion.HTTP_1_1, List.of(), null);
        return new HttpResponseEncodingContext(request, allocator);
    }

    @Nested
    @DisplayName("supports(Class<?>)")
    class Supports {

        @Test
        @DisplayName("supports is true for the sample payload type and false for a declared unsupported type")
        void supportsAnswersPerType() {
            HttpResponseBodyEncoder encoder = createEncoder();
            assertThat(encoder.supports(samplePayload().getClass()))
                    .as("supports(samplePayload().getClass())")
                    .isTrue();
            unsupportedPayloadType().ifPresent(type -> assertThat(encoder.supports(type))
                    .as("supports(%s) for a type the binding declares unsupported", type.getName())
                    .isFalse());
        }
    }

    @Nested
    @DisplayName("encode(payload, ctx)")
    class Encode {

        @Test
        @DisplayName("encode returns a non-null carrier with a non-null header list")
        void encodeReturnsNonNullCarrier() {
            HttpEncodedBody encoded = createEncoder().encode(samplePayload(), context());
            try {
                assertThat(encoded).isNotNull();
                assertThat(encoded.headers()).isNotNull();
            } finally {
                release(encoded);
            }
        }

        @Test
        @DisplayName("encode allocates exactly one live non-empty buffer through the context allocator")
        void encodeAllocatesOneLiveBufferThroughContextAllocator() {
            HttpEncodedBody encoded = createEncoder().encode(samplePayload(), context());
            try {
                assertThat(encoded.body())
                        .as("a payload that encodes to bytes must carry a buffer")
                        .isNotNull();
                assertThat(encoded.body().refCount())
                        .as("the returned buffer is live; ownership has transferred to the caller")
                        .isGreaterThan(0);
                assertThat(encoded.body().size())
                        .as("the buffer carries at least one encoded byte")
                        .isGreaterThan(0L);
                assertThat(encoded.body().size())
                        .as("size() never exceeds the memory the buffer can address")
                        .isLessThanOrEqualTo(encoded.body().segment().byteSize());
                assertThat(allocator.allocated())
                        .as("the body is allocated through HttpResponseEncodingContext#allocator()")
                        .isPositive();
                assertThat(allocator.outstanding())
                        .as("exactly one loan is live while the caller holds the body")
                        .isEqualTo(1L);
            } finally {
                release(encoded);
            }
            assertThat(allocator.outstanding())
                    .as("releasing the body returns the loan to the allocator")
                    .isZero();
        }

        @Test
        @DisplayName("encode reports size() as the number of encoded bytes when the binding pins them")
        void encodeReportsEncodedLength() {
            Optional<byte[]> expected = expectedSampleBody();
            assumeTrue(expected.isPresent(), "binding pins no sample body");
            byte[] bytes = expected.get();
            HttpEncodedBody encoded = createEncoder().encode(samplePayload(), context());
            try {
                assertThat(encoded.body().size())
                        .as("size() is the encoded length")
                        .isEqualTo(bytes.length);
                assertThat(readAll(encoded.body())).containsExactly(bytes);
            } finally {
                release(encoded);
            }
        }

        @Test
        @DisplayName("encode sets content-type to the declared media type")
        void encodeSetsContentType() {
            Optional<String> prefix = expectedContentTypePrefix();
            assumeTrue(prefix.isPresent(), "binding declares no content type");
            String expected = prefix.get();
            HttpEncodedBody encoded = createEncoder().encode(samplePayload(), context());
            try {
                HttpHeader contentType = encoded.headers().stream()
                        .filter(h -> h.nameEqualsIgnoreCase("content-type"))
                        .findFirst()
                        .orElse(null);
                assertThat(contentType)
                        .as("the encoding establishes a content-type header")
                        .isNotNull();
                assertThat(contentType.value().toLowerCase(Locale.ROOT))
                        .as("content-type starts with %s", expected)
                        .startsWith(expected.toLowerCase(Locale.ROOT));
            } finally {
                release(encoded);
            }
        }

        @Test
        @DisplayName("encode accepts a null payload and answers a non-null carrier without leaking")
        void encodeAcceptsNullPayload() {
            HttpEncodedBody encoded = createEncoder().encode(null, context());
            try {
                assertThat(encoded).as("a null payload is answered, not rejected").isNotNull();
                assertThat(encoded.headers()).isNotNull();
                if (encoded.body() != null) {
                    assertThat(encoded.body().refCount())
                            .as("a body returned for a null payload is live")
                            .isGreaterThan(0);
                }
            } finally {
                release(encoded);
            }
            assertThat(allocator.outstanding())
                    .as("releasing the null-payload answer leaves no loan")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Buffer ownership")
    class Ownership {

        @Test
        @DisplayName("two encodes return two independent live buffers; the first is not closed or retained")
        void encodesReturnIndependentLiveBuffers() {
            HttpResponseBodyEncoder encoder = createEncoder();
            HttpEncodedBody first = encoder.encode(samplePayload(), context());
            HttpEncodedBody second = encoder.encode(samplePayload(), context());
            try {
                byte[] firstBytes = readAll(first.body());
                assertThat(second.body())
                        .as("each encode returns its own buffer")
                        .isNotSameAs(first.body());
                assertThat(allocator.outstanding())
                        .as("two encodes hold two loans")
                        .isEqualTo(2L);
                assertThat(first.body().refCount())
                        .as("the first buffer stays live after a second encode")
                        .isGreaterThan(0);
                second.body().close();
                assertThat(first.body().refCount())
                        .as("releasing the second buffer does not touch the first")
                        .isGreaterThan(0);
                assertThat(readAll(first.body()))
                        .as("the first buffer's bytes are unchanged")
                        .containsExactly(firstBytes);
            } finally {
                release(first);
            }
            assertThat(allocator.outstanding()).isZero();
        }

        @Test
        @DisplayName("a failed encode releases every buffer it allocated")
        void failedEncodeReleasesItsBuffers() {
            Optional<Object> failing = failingPayload();
            assumeTrue(failing.isPresent(), "binding declares no failing payload");
            HttpResponseBodyEncoder encoder = createEncoder();
            Object payload = failing.orElseThrow();
            assertThatThrownBy(() -> encoder.encode(payload, context()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(allocator.outstanding())
                    .as("a failure leaves no loan outstanding")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void release(HttpEncodedBody encoded) {
        if (encoded != null && encoded.body() != null && encoded.body().refCount() > 0) {
            encoded.body().close();
        }
    }

    private static byte[] readAll(LoanedBuffer buf) {
        long size = buf.size();
        byte[] out = new byte[Math.toIntExact(size)];
        MemorySegment.copy(buf.segment(), 0L, MemorySegment.ofArray(out), 0L, size);
        return out;
    }

    /**
     * Counts the buffers handed out and still unreleased. Every allocation path increments the
     * count and the buffer's final close decrements it, so the count is the number of loans a
     * caller still owes the allocator.
     */
    private static final class TrackingAllocator implements MemoryAllocator {

        private final MemoryAllocator delegate;
        private final AtomicLong outstanding = new AtomicLong();
        private final AtomicLong allocated = new AtomicLong();

        TrackingAllocator(MemoryAllocator delegate) {
            this.delegate = delegate;
        }

        long outstanding() {
            return outstanding.get();
        }

        long allocated() {
            return allocated.get();
        }

        private LoanedBuffer track(LoanedBuffer buffer) {
            allocated.incrementAndGet();
            outstanding.incrementAndGet();
            buffer.addCloseAction(outstanding::decrementAndGet);
            return buffer;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return track(delegate.allocate(hint));
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return track(delegate.allocateNetwork(estimatedBytes));
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return track(delegate.allocateCarrierSlab(carrierIndex));
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return track(delegate.allocateInfrastructure(sizeBytes));
        }

        @Override
        public MemoryStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            // The wrapped allocator is owned by the TCK and closed once, after all tests.
        }
    }
}
