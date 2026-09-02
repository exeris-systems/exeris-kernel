/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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
 * TCK: Abstract base for {@link HttpRequestBodyDecoder} contract verification (ADR-036 §7).
 *
 * <p>Server-side mirror of {@link AbstractHttpResponseBodyDecoderTck}: the same
 * {@code decode} operation on the inbound request path. The only structural
 * difference is the context — {@link HttpRequestDecodingContext}
 * ({@code method}+{@code path}+headers+allocator) rather than
 * {@code HttpResponseDecodingContext} ({@code status}+headers+allocator).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code supports(Class<?>, String contentType)} matrix: a binding-supported
 *       content-type returns {@code true}; an {@code application/*+json}-style
 *       structured-syntax suffix returns {@code true} for a JSON binding; an
 *       unrelated content-type returns {@code false}. {@code contentType == null}
 *       and empty MUST be tolerated (no NPE) — the registry may still route by
 *       target type.</li>
 *   <li>{@code decode(body, targetType, ctx)} returns {@code Object} — the SPI is
 *       intentionally generics-free; the cast to {@code <T>} lives at the generated
 *       handler call-site.</li>
 *   <li><b>Empty-body tolerance.</b> A decoder MUST NOT throw on
 *       {@code body.size() == 0}; it returns {@code null}. This is exercised as
 *       <em>tolerance only</em>: on the default-wired path the generated handler
 *       short-circuits {@code Void}/bodyless requests <em>before</em> the decoder,
 *       so no caller relies on the empty-body return value.</li>
 *   <li><b>Null/empty content-type tolerance at decode time.</b> The decoder still
 *       decodes by target type when no content-type header was present.</li>
 *   <li><b>Secret safety across the whole cause chain.</b> Neither the thrown exception nor any
 *       link in its {@code getCause()} chain may carry body content — a malformed payload is the
 *       data most likely to be a secret posted to the wrong endpoint, and consumers log causes.</li>
 *   <li><b>Malformed-body classification (ADR-036 §2).</b> A body the decoder cannot bind
 *       MUST surface as {@link RequestBodyDecodeException}, distinct from the
 *       {@link IllegalStateException} a missing decoder raises. The handler is required to
 *       answer {@code 400} for the first and {@code 5xx} for the second, and can only do that
 *       if the types differ — until 0.12 both were {@code IllegalStateException} and every
 *       malformed request became a 500.</li>
 *   <li><b>Driver exception opacity (The Wall).</b> No binding-specific type — no
 *       {@code tools.jackson.*} — may cross the SPI boundary. The wrapper is either a
 *       {@code java.*} exception or an SPI-owned one; opacity is about keeping <em>driver</em>
 *       types out, not about restricting the kernel to {@code java.*}.</li>
 *   <li><b>Priority ordering.</b> {@link HttpRequestBodyDecoderRegistry#of(List)}
 *       resolves the higher-{@link HttpRequestBodyDecoder#priority()} candidate;
 *       ties resolve by registration order.</li>
 *   <li>Buffer ownership: the decoder MUST NOT close, retain, or extend the
 *       lifetime of the buffer. The TCK verifies the buffer is still live and its
 *       refCount is unchanged after a successful {@code decode}.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityJsonRequestBodyDecoderTckTest extends AbstractHttpRequestBodyDecoderTck {
 *     @Override
 *     protected HttpRequestBodyDecoder createDecoder() {
 *         return new CommunityJsonRequestBodyDecoder(JsonMapper.builder().build());
 *     }
 *
 *     @Override
 *     protected String supportedContentType() {
 *         return "application/json";
 *     }
 *
 *     @Override
 *     protected String structuredSuffixContentType() {
 *         return "application/merge-patch+json";
 *     }
 *
 *     @Override
 *     protected byte[] validEncodedBytes() {
 *         return "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
 *     }
 *
 *     @Override
 *     protected byte[] malformedEncodedBytes() {
 *         return "{ \"k\": ".getBytes(StandardCharsets.UTF_8);
 *     }
 *
 *     @Override
 *     protected Class<?> validTargetType() {
 *         return Map.class;
 *     }
 * }
 * }</pre>
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>This TCK imports only from {@code exeris-kernel-spi}. Drivers may not leak
 * their binding-library types onto the TCK surface.
 *
 * @since 0.8.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHttpRequestBodyDecoderTck {

    /** UTF-8 charset constant; subclasses may reference this for fixtures. */
    @SuppressWarnings("unused")
    protected static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    private MemoryAllocator allocator;

    /**
     * @return the decoder under test; never null
     */
    protected abstract HttpRequestBodyDecoder createDecoder();

    /**
     * Returns one content-type the decoder claims support for (e.g.
     * {@code "application/json"} for a JSON decoder). Drives positive
     * {@code supports(...)} cases.
     *
     * @return non-null lower-case content-type
     */
    protected abstract String supportedContentType();

    /**
     * Returns an {@code application/*+json}-style structured-syntax-suffix
     * content-type the decoder MUST also support (e.g.
     * {@code "application/merge-patch+json"}), per RFC 6838 §4.2.8.
     *
     * @return non-null structured-suffix content-type
     */
    protected abstract String structuredSuffixContentType();

    /**
     * Returns a byte payload the decoder can successfully decode into
     * {@link #validTargetType()} (e.g. {@code "{\"k\":\"v\"}".getBytes(UTF_8)}).
     *
     * @return non-null, non-empty byte payload
     */
    protected abstract byte[] validEncodedBytes();

    /**
     * Returns a byte payload that is syntactically invalid for the binding (e.g.
     * truncated JSON) — exercised for the driver-exception-opacity clause.
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

    private HttpRequestDecodingContext context() {
        return new HttpRequestDecodingContext(HttpMethod.POST, "/tck", List.of(), allocator);
    }

    /** The allocator-less shape: what a caller with nothing to offer builds (0.12). */
    private static HttpRequestDecodingContext contextWithoutAllocator() {
        return new HttpRequestDecodingContext(HttpMethod.POST, "/tck", List.of());
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
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThat(decoder.supports(validTargetType(), supportedContentType()))
                    .as("decoder must claim support for its own (targetType, contentType)")
                    .isTrue();
        }

        @Test
        @DisplayName("supports returns true for an application/*+json structured-syntax suffix")
        void supportsStructuredSuffix() {
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThat(decoder.supports(validTargetType(), structuredSuffixContentType()))
                    .as("decoder must accept the structured-syntax suffix (RFC 6838 §4.2.8): %s",
                            structuredSuffixContentType())
                    .isTrue();
        }

        @Test
        @DisplayName("supports tolerates contentType == null (client omitted the header)")
        void supportsTolerantOfNullContentType() {
            HttpRequestBodyDecoder decoder = createDecoder();
            // Contract: MUST tolerate null content-type without throwing. The decoder
            // may still return true (registry routes by target type) or false per its
            // own policy, but it MUST NOT throw an NPE.
            Assertions.assertDoesNotThrow(
                    () -> decoder.supports(validTargetType(), null),
                    "supports(...) must not throw when contentType == null");
        }

        @Test
        @DisplayName("supports tolerates empty contentType string")
        void supportsTolerantOfEmptyContentType() {
            HttpRequestBodyDecoder decoder = createDecoder();
            Assertions.assertDoesNotThrow(
                    () -> decoder.supports(validTargetType(), ""),
                    "supports(...) must not throw when contentType is empty");
        }

        @Test
        @DisplayName("supports returns false for an unrelated content type (text/plain)")
        void supportsRejectsUnsupportedContentType() {
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThat(decoder.supports(validTargetType(), "text/plain"))
                    .as("decoder must decline an unrelated content type when one is supplied")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("decode(body, targetType, ctx)")
    class Decode {

        @Test
        @DisplayName("decode round-trips valid input bytes into a validTargetType instance")
        void decodeHappyPath() {
            HttpRequestBodyDecoder decoder = createDecoder();
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
        @DisplayName("decode works when the context carries no allocator")
        void decodeWithoutAllocator() {
            // The allocator was mandatory on this context until 0.12, which made every decode site
            // require a bound MEMORY_ALLOCATOR even though no decoder reads one — a decoder is handed
            // an already-allocated buffer. A decoder that needs auxiliary off-heap memory must take an
            // allocator at construction; reaching for the context's is what this forbids.
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                Object decoded = decoder.decode(body, validTargetType(), contextWithoutAllocator());
                assertThat(decoded)
                        .as("decode must not depend on the context carrying an allocator")
                        .isNotNull();
                assertThat(validTargetType().isInstance(decoded)).isTrue();
            }
        }

        @Test
        @DisplayName("decode does NOT close or extend lifetime of the buffer (refCount unchanged)")
        void decodeDoesNotMutateRefCount() {
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                int before = body.refCount();
                decoder.decode(body, validTargetType(), context());
                int after = body.refCount();
                assertThat(after)
                        .as("decoder MUST NOT mutate the buffer refCount; caller owns lifecycle")
                        .isEqualTo(before);
                assertThat(body.isAlive())
                        .as("decoder MUST NOT close the buffer; caller closes when the exchange ends")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("decode TOLERATES an empty body (returns null) — tolerance only; "
                + "the generated handler short-circuits Void/bodyless BEFORE the decoder")
        void decodeToleratesEmptyBody() {
            // Intent: this asserts tolerance, NOT a contract a caller relies on. On the
            // default-wired path bodyless/Void requests never reach the decoder (ADR-036
            // §1 "Never sees Void.class"); the decoder must simply not throw on size == 0.
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(new byte[0])) {
                Object decoded = Assertions.assertDoesNotThrow(
                        () -> decoder.decode(body, validTargetType(), context()),
                        "decode must not throw on body.size() == 0");
                assertThat(decoded)
                        .as("empty-body decode returns null (tolerance contract)")
                        .isNull();
            }
        }

        @Test
        @DisplayName("decode tolerates a null/empty content-type — decodes by target type")
        void decodeToleratesNullContentType() {
            // The registry may route a decoder when no content-type header was present;
            // decode() carries no content-type itself, so a successful decode here proves
            // the decoder does not depend on a content-type to do its work.
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThat(decoder.supports(validTargetType(), null))
                    .as("Community-family decoders route by target type when content-type is absent")
                    .isTrue();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                Object decoded = decoder.decode(body, validTargetType(), context());
                assertThat(decoded)
                        .as("decode succeeds independently of the request content-type")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("decode wraps driver exceptions — no tools.jackson.* escapes")
        void decodeWrapsDriverExceptions() {
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(malformedEncodedBytes())) {
                assertThatThrownBy(() -> decoder.decode(body, validTargetType(), context()))
                        .as("driver exceptions MUST be wrapped — no binding-specific types may escape")
                        .isInstanceOf(RuntimeException.class)
                        .extracting(Object::getClass)
                        .satisfies(cls -> {
                            String pkg = ((Class<?>) cls).getPackage().getName();
                            // The wrapper may be a java.* exception or an SPI-owned one. Requiring
                            // java.* is what previously forced malformed bodies to share a type with
                            // configuration errors; opacity only ever meant "no driver types".
                            assertThat(pkg)
                                    .as("wrapped exception MUST be java.* or SPI-owned, never the driver's")
                                    .matches(p -> p.startsWith("java.") || p.startsWith("eu.exeris.kernel.spi."));
                            assertThat(pkg)
                                    .as("no tools.jackson.* type may cross the SPI boundary (The Wall)")
                                    .doesNotStartWith("tools.jackson");
                        });
            }
        }

        @Test
        @DisplayName("a malformed body is classifiable as a caller fault, not a server one (ADR-036 §2)")
        void decodeClassifiesMalformedBodyAsCallerFault() {
            // The contract a generated handler actually depends on. It answers 400 for a body it
            // cannot decode and 5xx for a decoder it cannot resolve, and it distinguishes them by
            // type — never by matching on a message. A decoder that raises IllegalStateException
            // here makes the handler blame the server for the caller's typo.
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(malformedEncodedBytes())) {
                assertThatThrownBy(() -> decoder.decode(body, validTargetType(), context()))
                        .as("a body that will not bind is the caller's fault and must be typed as one")
                        .isInstanceOf(RequestBodyDecodeException.class)
                        .isNotInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("the malformed-body failure carries its code and args, and never the body")
        void malformedBodyFailureIsSecretSafe() {
            // Glass-Box, with the one constraint that matters here: a payload that failed to parse
            // is exactly the kind of data most likely to be a secret posted to the wrong endpoint,
            // so rawArgs carry the target type and the length and nothing else.
            HttpRequestBodyDecoder decoder = createDecoder();
            byte[] malformed = malformedEncodedBytes();
            try (LoanedBuffer body = bufferOf(malformed)) {
                assertThatThrownBy(() -> decoder.decode(body, validTargetType(), context()))
                        .isInstanceOfSatisfying(RequestBodyDecodeException.class, e -> {
                            assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4013);
                            assertThat(e.rawArgs())
                                    .as("index 0 targetTypeName, index 1 bodySize")
                                    .containsExactly(validTargetType().getName(), (long) malformed.length);
                            String payload = new String(malformed, StandardCharsets.UTF_8);
                            assertThat(e.getMessage())
                                    .as("the message is static — no body content is formatted into it")
                                    .doesNotContain(payload);

                            // The exception is not the only thing a consumer logs. Every stack-trace
                            // printer and logging bridge walks the cause chain, so a driver that
                            // retains a binding failure whose own message quotes the offending input
                            // publishes the body through the back door — the outer type's guarantee
                            // would be true and useless. Jackson 3 redacts the source snippet by
                            // default (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` is off, unlike
                            // Jackson 2), so the Community binding passes this as shipped; that is a
                            // library default an application's JsonMapperCustomizer can flip, which
                            // is exactly why the contract holds the whole chain rather than trusting
                            // one. Bounded traversal: a self-referencing cause must not hang the TCK.
                            int depth = 0;
                            for (Throwable link = e; link != null && depth < 16;
                                    link = link.getCause(), depth++) {
                                if (link.getMessage() != null) {
                                    assertThat(link.getMessage())
                                            .as("no link in the cause chain may carry body content (%s)",
                                                    link.getClass().getName())
                                            .doesNotContain(payload);
                                }
                            }
                        });
            }
        }

        @Test
        @DisplayName("decode returns Object (generics-free SPI surface)")
        void decodeReturnsObjectType() {
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                Object decoded = decoder.decode(body, validTargetType(), context());
                Object viaObject = decoded;
                assertThat(viaObject).isSameAs(decoded);
            }
        }

        @Test
        @DisplayName("decode rejects a null body buffer")
        void decodeRejectsNullBody() {
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThatThrownBy(() -> decoder.decode(null, validTargetType(), context()))
                    .as("null body must be rejected, not dereferenced")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("decode rejects a null targetType")
        void decodeRejectsNullTargetType() {
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                assertThatThrownBy(() -> decoder.decode(body, null, context()))
                        .as("null targetType must be rejected")
                        .isInstanceOf(RuntimeException.class);
            }
        }

        @Test
        @DisplayName("decode rejects a null context")
        void decodeRejectsNullContext() {
            HttpRequestBodyDecoder decoder = createDecoder();
            try (LoanedBuffer body = bufferOf(validEncodedBytes())) {
                assertThatThrownBy(() -> decoder.decode(body, validTargetType(), null))
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
            HttpRequestBodyDecoder decoder = createDecoder();
            assertThat(decoder.priority())
                    .as("priority() must be non-negative for the decoder to participate in resolution")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("HttpRequestBodyDecoderRegistry.of(...) priority ordering")
    class RegistryOrdering {

        @Test
        @DisplayName("higher priority wins when two decoders both support a type")
        void higherPriorityWins() {
            StubDecoder low = new StubDecoder(1);
            StubDecoder high = new StubDecoder(5);
            // Registration order deliberately puts the lower-priority one first to prove
            // resolution is by priority, not by insertion order.
            HttpRequestBodyDecoderRegistry registry =
                    HttpRequestBodyDecoderRegistry.of(List.of(low, high));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("registry MUST resolve the higher-priority decoder")
                    .isSameAs(high);
        }

        @Test
        @DisplayName("ties resolve by registration order (first-registered wins)")
        void tiesResolveByRegistrationOrder() {
            StubDecoder first = new StubDecoder(3);
            StubDecoder second = new StubDecoder(3);
            HttpRequestBodyDecoderRegistry registry =
                    HttpRequestBodyDecoderRegistry.of(List.of(first, second));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("equal priority MUST resolve the first-registered decoder")
                    .isSameAs(first);
        }

        @Test
        @DisplayName("resolve returns null when no candidate supports the inputs")
        void resolveReturnsNullWhenUnsupported() {
            StubDecoder declining = new StubDecoder(0, false);
            HttpRequestBodyDecoderRegistry registry =
                    HttpRequestBodyDecoderRegistry.of(List.of(declining));
            assertThat(registry.resolve(validTargetType(), supportedContentType()))
                    .as("registry MUST return null when no decoder supports the pair")
                    .isNull();
        }
    }

    /**
     * SPI-only stub used to exercise registry priority ordering without pulling any
     * driver-library type onto the TCK surface (The Wall).
     */
    private static final class StubDecoder implements HttpRequestBodyDecoder {
        private final int priority;
        private final boolean supports;

        StubDecoder(int priority) {
            this(priority, true);
        }

        StubDecoder(int priority, boolean supports) {
            this.priority = priority;
            this.supports = supports;
        }

        @Override
        public boolean supports(Class<?> targetType, String contentType) {
            return supports;
        }

        @Override
        public Object decode(LoanedBuffer body, Class<?> targetType, HttpRequestDecodingContext context) {
            return null;
        }

        @Override
        public int priority() {
            return priority;
        }
    }
}
