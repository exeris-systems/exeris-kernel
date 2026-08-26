/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Neutral response body decoder contract for typed inbound HTTP response bodies.
 *
 * <p>Implementations are provider-side and transport-agnostic: they parse the
 * wire-format response body into a typed payload. Mirrors the client-side
 * {@link HttpRequestBodyEncoder} on the response path; both SPI surfaces remain
 * generics-free intentionally — the cast site lives at the façade
 * (single {@code @SuppressWarnings("unchecked")}) so that alternative bindings
 * (Jackson {@code TypeReference}, Protobuf descriptors) do not require {@code <T>}
 * propagation through the SPI.
 *
 * <h2>Body ownership</h2>
 * <p>The {@link LoanedBuffer} passed to {@link #decode} is read-only from the
 * decoder's perspective: the decoder MUST NOT close, retain, or otherwise
 * extend the lifetime of the buffer. The façade releases the buffer after
 * decoding completes (in a {@code finally} block on the response side).
 *
 * <h2>Empty-body tolerance</h2>
 * <p>Implementations MAY return {@code null} when {@code body.size() == 0} and
 * {@code targetType} is nullable; the façade
 * ({@code KernelWebClient.execute}) decides whether {@code null} is acceptable
 * per call. The façade short-circuits {@code Void.class} before calling the
 * decoder, so decoders never see {@code Void.class}.
 *
 * <h2>Content-type tolerance</h2>
 * <p>Implementations MUST tolerate {@code contentType == null} or empty
 * (server omitted the header). The registry may still route a decoder that
 * claims support for {@code targetType} when {@code contentType} is missing.
 *
 * <h2>Driver exception wrapping</h2>
 * <p>Implementations MUST wrap binding-specific exceptions (e.g., Jackson
 * {@code JacksonException}) into a generic {@link RuntimeException} (typically
 * {@link IllegalStateException}) before returning. No driver-specific exception
 * types may cross the SPI boundary.
 *
 * @since 0.8.0
 */
public interface HttpResponseBodyDecoder {

    /**
     * Returns true when this decoder can decode the given target type from the
     * given content type.
     *
     * <p>Implementations MUST tolerate {@code contentType == null} or empty;
     * the registry may still return this decoder when no content-type header
     * was present on the response.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType response {@code content-type} header value, or {@code null}/empty
     * @return true when this decoder can produce {@code targetType} from a body of {@code contentType}
     */
    boolean supports(Class<?> targetType, String contentType);

    /**
     * Decodes the off-heap response body into a typed payload instance.
     *
     * <p>The returned value is the decoder's typed representation of {@code body};
     * the cast to the caller's static {@code <T>} is performed at the façade
     * call site. Implementations MUST NOT close the buffer; the façade owns
     * its lifecycle.
     *
     * @param body       response body buffer; never null (the façade short-circuits null/empty cases as documented)
     * @param targetType desired payload runtime class; never null
     * @param context    decoding context (status, headers, allocator); never null
     * @return decoded payload instance, or {@code null} when the body is empty and the implementation tolerates it
     */
    Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context);

    /**
     * Decoder priority. Higher value wins when multiple decoders support a
     * {@code (targetType, contentType)} pair.
     *
     * @return priority, default 0
     */
    default int priority() {
        return 0;
    }
}
