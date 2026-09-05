/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Neutral request body decoder contract for typed inbound HTTP request bodies.
 *
 * <p>Implementations are provider-side and transport-agnostic: they parse the
 * wire-format request body into a typed payload (e.g. a {@code Widget} for
 * {@code POST /widgets}). Mirrors the client-side
 * {@link HttpResponseBodyDecoder} on the request path; both SPI surfaces remain
 * generics-free intentionally — the cast site lives at the resolution call site
 * (single {@code @SuppressWarnings("unchecked")} in the generated handler) so that
 * alternative bindings (Jackson {@code TypeReference}, Protobuf descriptors) do not
 * require {@code <T>} propagation through the SPI.
 *
 * <h2>Body ownership</h2>
 * <p>The {@link LoanedBuffer} passed to {@link #decode} is read-only from the
 * decoder's perspective: the decoder MUST NOT close, retain, or otherwise
 * extend the lifetime of the buffer. The caller (the generated handler) owns the
 * buffer's lifecycle — the request body buffer is owned by the transport/codec and
 * released when the exchange ends.
 *
 * <h2>Empty-body / {@code Void} tolerance</h2>
 * <p>The resolution site short-circuits bodyless / {@code Void.class} requests
 * before invoking the decoder (the generated handler only calls the decoder for
 * verbs that carry an entity body), so decoders never see {@code Void.class}.
 *
 * <h2>Content-type tolerance</h2>
 * <p>Implementations MUST tolerate {@code contentType == null} or empty
 * (client omitted the header). The registry may still route a decoder that
 * claims support for {@code targetType} when {@code contentType} is missing.
 *
 * <h2>Driver exception wrapping</h2>
 * <p>Implementations MUST wrap binding-specific exceptions (e.g., Jackson
 * {@code JacksonException}) before returning; no driver-specific exception type may
 * cross the SPI boundary. Which wrapper is not free choice — the two failure classes
 * are distinguished by <em>type</em>, because ADR-036 §2 puts status mapping on the
 * handler and a handler cannot map what it cannot tell apart:
 * <ul>
 *   <li><b>A body the decoder cannot bind</b> (malformed syntax, wrong shape) MUST surface as
 *       {@link eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException} — a caller fault,
 *       {@code 400 Bad Request}.</li>
 *   <li><b>A missing or unresolvable decoder</b> stays an {@link IllegalStateException} — a
 *       deployment fault, {@code 5xx}, never downgraded to 400.</li>
 * </ul>
 *
 * <p>Until 0.12 both arrived as {@code IllegalStateException} and the mandated mapping was
 * therefore not expressible: every malformed request reached the caller as a 500.
 *
 * @since 0.8
 */
public interface HttpRequestBodyDecoder {

    /**
     * Returns true when this decoder can decode the given target type from the
     * given content type.
     *
     * <p>Implementations MUST tolerate {@code contentType == null} or empty;
     * the registry may still return this decoder when no content-type header
     * was present on the request.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType request {@code content-type} header value, or {@code null}/empty
     * @return true when this decoder can produce {@code targetType} from a body of {@code contentType}
     */
    boolean supports(Class<?> targetType, String contentType);

    /**
     * Decodes the off-heap request body into a typed payload instance.
     *
     * <p>The returned value is the decoder's typed representation of {@code body};
     * the cast to the caller's static {@code <T>} is performed at the resolution
     * call site (the generated handler). Implementations MUST NOT close the buffer;
     * the caller owns its lifecycle.
     *
     * @param body       request body buffer; never null (the caller short-circuits null/empty cases as documented)
     * @param targetType desired payload runtime class; never null
     * @param context    decoding context (method, path, headers, allocator); never null
     * @return decoded payload instance, or {@code null} when the body is empty and the implementation tolerates it
     */
    Object decode(LoanedBuffer body, Class<?> targetType, HttpRequestDecodingContext context);

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
