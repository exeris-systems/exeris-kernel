/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.codec.EventCodecContext;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodec;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link EventPayloadCodec} contract verification (ADR-046).
 *
 * <p>The event-side analog of {@code AbstractHttpRequestBodyDecoderTck}. It asserts
 * the encode/decode round-trip, registry priority resolution, empty-payload and
 * null/empty-content-type tolerance, RAII ownership of the supplied/returned
 * {@link EventPayload}, and driver-exception opacity (The Wall).
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>This TCK imports only from {@code exeris-kernel-spi}. Drivers may not leak their
 * binding-library types onto the TCK surface. The {@link EventPayload} fixtures are a
 * minimal SPI-only heap implementation, so no Core/Community payload type is required.
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityJsonEventPayloadCodecTckTest extends AbstractEventPayloadCodecTck {
 *     @Override protected EventPayloadCodec createCodec() {
 *         return new CommunityJsonEventPayloadCodec(JsonMapper.builder().build());
 *     }
 *     @Override protected String supportedContentType() { return "application/json"; }
 *     @Override protected String structuredSuffixContentType() { return "application/vnd.exeris+json"; }
 *     @Override protected Object validPayloadObject() { return Map.of("k", "v"); }
 *     @Override protected Class<?> validTargetType() { return Map.class; }
 *     @Override protected byte[] malformedEncodedBytes() { return "[".getBytes(UTF_8); }
 * }
 * }
 *
 * @since 0.10
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractEventPayloadCodecTck {

    /** UTF-8 charset constant; subclasses may reference this for fixtures. */
    @SuppressWarnings("unused")
    protected static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    /**
     * Creates the {@link EventPayloadCodec} under test.
     *
     * @return the codec under test; never null
     */
    protected abstract EventPayloadCodec createCodec();

    /**
     * Returns one content-type the codec claims support for (e.g. {@code "application/json"}).
     *
     * @return non-null content-type
     */
    protected abstract String supportedContentType();

    /**
     * Returns an {@code application/*+json}-style structured-syntax-suffix content-type
     * the codec MUST also support (RFC 6838 §4.2.8).
     *
     * @return non-null structured-suffix content-type
     */
    protected abstract String structuredSuffixContentType();

    /**
     * Returns a payload object the codec can encode and that decodes back, via
     * {@link #validTargetType()}, to an instance equal to this object (e.g.
     * {@code Map.of("k", "v")}).
     *
     * @return non-null payload object that round-trips equal
     */
    protected abstract Object validPayloadObject();

    /**
     * Returns the target class {@link #validPayloadObject()} decodes into.
     *
     * @return non-null target type
     */
    protected abstract Class<?> validTargetType();

    /**
     * Returns a byte payload that is syntactically invalid for the binding (e.g.
     * truncated JSON) — exercised for the decode driver-exception-opacity clause.
     *
     * @return non-null, non-empty byte payload
     */
    protected abstract byte[] malformedEncodedBytes();

    /**
     * Returns a payload object the binding cannot serialize (e.g. a value with no
     * serializable properties, or a self-referential cycle) — exercised for the
     * <em>encode</em> driver-exception-opacity clause. The codec contract requires
     * {@code encode} to wrap binding exceptions into a {@code java.*}
     * {@link RuntimeException} just as {@code decode} does.
     *
     * @return non-null payload object that fails to encode under this binding
     */
    protected abstract Object unserializablePayloadObject();

    private EventCodecContext context() {
        return new EventCodecContext(supportedContentType(), "TckEvent");
    }

    @Nested
    @DisplayName("supports(Class<?>, String)")
    class Supports {

        @Test
        @DisplayName("supports returns true for (validTargetType, supportedContentType)")
        void supportsHappyPath() {
            assertThat(createCodec().supports(validTargetType(), supportedContentType()))
                    .as("codec must claim support for its own (payloadType, contentType)")
                    .isTrue();
        }

        @Test
        @DisplayName("supports returns true for an application/*+json structured-syntax suffix")
        void supportsStructuredSuffix() {
            assertThat(createCodec().supports(validTargetType(), structuredSuffixContentType()))
                    .as("codec must accept the structured-syntax suffix (RFC 6838 §4.2.8): %s",
                            structuredSuffixContentType())
                    .isTrue();
        }

        @Test
        @DisplayName("supports tolerates contentType == null (producer relies on the default)")
        void supportsTolerantOfNullContentType() {
            Assertions.assertDoesNotThrow(
                    () -> createCodec().supports(validTargetType(), null),
                    "supports(...) must not throw when contentType == null");
        }

        @Test
        @DisplayName("supports tolerates empty contentType string")
        void supportsTolerantOfEmptyContentType() {
            Assertions.assertDoesNotThrow(
                    () -> createCodec().supports(validTargetType(), ""),
                    "supports(...) must not throw when contentType is empty");
        }

        @Test
        @DisplayName("supports returns false for an unrelated content type (text/plain)")
        void supportsRejectsUnsupportedContentType() {
            assertThat(createCodec().supports(validTargetType(), "text/plain"))
                    .as("codec must decline an unrelated content type when one is supplied")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("encode(payload, ctx)")
    class Encode {

        @Test
        @DisplayName("encode returns a caller-owned, bytes-backed payload (refCount == 1, alive, non-empty)")
        void encodeProducesOwnedPayload() {
            try (EventPayload encoded = createCodec().encode(validPayloadObject(), context())) {
                assertThat(encoded).as("encode must return a non-null payload").isNotNull();
                assertThat(encoded.refCount())
                        .as("encoded payload must be caller-owned with refCount == 1")
                        .isEqualTo(1);
                assertThat(encoded.isAlive()).as("encoded payload must be alive").isTrue();
                assertThat(encoded.length())
                        .as("a non-empty payload object must encode to a non-empty byte payload")
                        .isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("encode wraps driver exceptions into a generic RuntimeException — no tools.jackson.* escapes")
        void encodeWrapsDriverExceptions() {
            EventPayloadCodec codec = createCodec();
            assertThatThrownBy(() -> codec.encode(unserializablePayloadObject(), context()))
                    .as("encode driver exceptions MUST be wrapped — no binding-specific types may escape")
                    .isInstanceOf(RuntimeException.class)
                    .extracting(Object::getClass)
                    .satisfies(cls -> {
                        String pkg = ((Class<?>) cls).getPackage().getName();
                        assertThat(pkg)
                                .as("wrapped exception MUST live in java.* (not the driver package)")
                                .startsWith("java.");
                        assertThat(pkg)
                                .as("no tools.jackson.* type may cross the SPI boundary (The Wall)")
                                .doesNotStartWith("tools.jackson");
                    });
        }

        @Test
        @DisplayName("encode rejects a null payload")
        void encodeRejectsNullPayload() {
            assertThatThrownBy(() -> createCodec().encode(null, context()))
                    .as("null payload must be rejected")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("encode rejects a null context")
        void encodeRejectsNullContext() {
            assertThatThrownBy(() -> createCodec().encode(validPayloadObject(), null))
                    .as("null context must be rejected")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("decode(payload, targetType, ctx)")
    class Decode {

        @Test
        @DisplayName("encode → decode round-trips into a validTargetType instance equal to the source")
        void roundTrip() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload encoded = codec.encode(validPayloadObject(), context())) {
                Object decoded = codec.decode(encoded, validTargetType(), context());
                assertThat(decoded)
                        .as("decode must return a non-null value for a non-empty payload")
                        .isNotNull();
                assertThat(validTargetType().isInstance(decoded))
                        .as("decoded value must be an instance of validTargetType()")
                        .isTrue();
                assertThat(decoded)
                        .as("encode → decode must round-trip to an equal value")
                        .isEqualTo(validPayloadObject());
            }
        }

        @Test
        @DisplayName("decode does NOT close or extend lifetime of the payload (refCount unchanged, still alive)")
        void decodeDoesNotMutateRefCount() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload encoded = codec.encode(validPayloadObject(), context())) {
                int before = encoded.refCount();
                codec.decode(encoded, validTargetType(), context());
                assertThat(encoded.refCount())
                        .as("decoder MUST NOT mutate the payload refCount; caller owns lifecycle")
                        .isEqualTo(before);
                assertThat(encoded.isAlive())
                        .as("decoder MUST NOT close the payload; caller owns lifecycle")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("decode TOLERATES an empty payload (returns null) — tolerance only")
        void decodeToleratesEmptyPayload() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload empty = HeapPayload.of(new byte[0])) {
                Object decoded = Assertions.assertDoesNotThrow(
                        () -> codec.decode(empty, validTargetType(), context()),
                        "decode must not throw on length() == 0");
                assertThat(decoded).as("empty-payload decode returns null (tolerance contract)").isNull();
            }
        }

        @Test
        @DisplayName("decode wraps driver exceptions into a generic RuntimeException — no tools.jackson.* escapes")
        void decodeWrapsDriverExceptions() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload malformed = HeapPayload.of(malformedEncodedBytes())) {
                assertThatThrownBy(() -> codec.decode(malformed, validTargetType(), context()))
                        .as("driver exceptions MUST be wrapped — no binding-specific types may escape")
                        .isInstanceOf(RuntimeException.class)
                        .extracting(Object::getClass)
                        .satisfies(cls -> {
                            String pkg = ((Class<?>) cls).getPackage().getName();
                            assertThat(pkg)
                                    .as("wrapped exception MUST live in java.* (not the driver package)")
                                    .startsWith("java.");
                            assertThat(pkg)
                                    .as("no tools.jackson.* type may cross the SPI boundary (The Wall)")
                                    .doesNotStartWith("tools.jackson");
                        });
            }
        }

        @Test
        @DisplayName("decode rejects a null payload")
        void decodeRejectsNullPayload() {
            assertThatThrownBy(() -> createCodec().decode(null, validTargetType(), context()))
                    .as("null payload must be rejected, not dereferenced")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("decode rejects a null targetType")
        void decodeRejectsNullTargetType() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload encoded = codec.encode(validPayloadObject(), context())) {
                assertThatThrownBy(() -> codec.decode(encoded, null, context()))
                        .as("null targetType must be rejected")
                        .isInstanceOf(RuntimeException.class);
            }
        }

        @Test
        @DisplayName("decode rejects a null context")
        void decodeRejectsNullContext() {
            EventPayloadCodec codec = createCodec();
            try (EventPayload encoded = codec.encode(validPayloadObject(), context())) {
                assertThatThrownBy(() -> codec.decode(encoded, validTargetType(), null))
                        .as("null context must be rejected")
                        .isInstanceOf(RuntimeException.class);
            }
        }
    }

    @Nested
    @DisplayName("priority()")
    class Priority {

        @Test
        @DisplayName("priority() returns a non-negative value (default 0)")
        void defaultPriorityIsNonNegative() {
            assertThat(createCodec().priority())
                    .as("priority() must be non-negative for the codec to participate in resolution")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("EventPayloadCodecRegistry.of(...) priority ordering")
    class RegistryOrdering {

        @Test
        @DisplayName("higher priority wins when two codecs both support a type")
        void higherPriorityWins() {
            StubCodec low = new StubCodec(1);
            StubCodec high = new StubCodec(5);
            // Registration order deliberately puts the lower-priority one first to prove
            // resolution is by priority, not by insertion order.
            EventPayloadCodecRegistry registry = EventPayloadCodecRegistry.of(List.of(low, high));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("registry MUST resolve the higher-priority codec")
                    .isSameAs(high);
        }

        @Test
        @DisplayName("ties resolve by registration order (first-registered wins)")
        void tiesResolveByRegistrationOrder() {
            StubCodec first = new StubCodec(3);
            StubCodec second = new StubCodec(3);
            EventPayloadCodecRegistry registry = EventPayloadCodecRegistry.of(List.of(first, second));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("equal priority MUST resolve the first-registered codec")
                    .isSameAs(first);
        }

        @Test
        @DisplayName("resolve returns null when no candidate supports the inputs")
        void resolveReturnsNullWhenUnsupported() {
            StubCodec declining = new StubCodec(0, false);
            EventPayloadCodecRegistry registry = EventPayloadCodecRegistry.of(List.of(declining));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("registry MUST return null when no codec supports the pair")
                    .isNull();
        }
    }

    /**
     * SPI-only stub used to exercise registry priority ordering without pulling any
     * driver-library type onto the TCK surface (The Wall).
     */
    private static final class StubCodec implements EventPayloadCodec {
        private final int priority;
        private final boolean supports;

        StubCodec(int priority) {
            this(priority, true);
        }

        StubCodec(int priority, boolean supports) {
            this.priority = priority;
            this.supports = supports;
        }

        @Override
        public boolean supports(Class<?> payloadType, String contentType) {
            return supports;
        }

        @Override
        public EventPayload encode(Object payload, EventCodecContext ctx) {
            return HeapPayload.of(new byte[0]);
        }

        @Override
        public Object decode(EventPayload payload, Class<?> targetType, EventCodecContext ctx) {
            return null;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    /**
     * Minimal SPI-only heap {@link EventPayload} for fixtures (empty / malformed bytes)
     * with an observable refCount, so the ownership assertions can run without depending
     * on any Core/Community payload type.
     */
    private static final class HeapPayload implements EventPayload {
        private final byte[] bytes;
        private final AtomicInteger refCount = new AtomicInteger(1);

        private HeapPayload(byte[] bytes) {
            this.bytes = bytes;
        }

        static HeapPayload of(byte[] bytes) {
            return new HeapPayload(bytes);
        }

        @Override
        public MemorySegment segment() {
            if (refCount.get() <= 0) {
                throw new IllegalStateException("payload already released");
            }
            return MemorySegment.ofArray(bytes).asReadOnly();
        }

        @Override
        public int length() {
            return bytes.length;
        }

        @Override
        public void retain() {
            refCount.incrementAndGet();
        }

        @Override
        public void close() {
            refCount.updateAndGet(c -> c <= 0 ? 0 : c - 1);
        }

        @Override
        public int refCount() {
            return Math.max(0, refCount.get());
        }

        @Override
        public boolean isAlive() {
            return refCount.get() > 0;
        }

        @SuppressWarnings("unused")
        byte[] toArray() {
            return MemorySegment.ofArray(bytes).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
