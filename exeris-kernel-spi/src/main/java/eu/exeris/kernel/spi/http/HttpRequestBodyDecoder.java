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
 * <p>The resolution site short-circuits bodyless and {@code Void.class} requests before invoking a
 * decoder — the generated handler calls one only for verbs that carry an entity body — so a decoder
 * never sees {@code Void.class}.
 *
 * <p><b>Allocation:</b> allocates (the decoded payload {@link #decode} returns); the body is read
 * from the loaned segment in place, and no decoder is given a heap copy of it
 * <p><b>Ownership:</b> the decoder owns nothing — the request body buffer belongs to the
 * transport/codec that produced it and is released when the exchange ends
 *
 * @implSpec An implementation:
 *           <ul>
 *             <li>MUST NOT close, retain, or otherwise extend the lifetime of the
 *                 {@link LoanedBuffer} it is handed; it is read-only from the decoder's
 *                 perspective;</li>
 *             <li>MUST tolerate a {@code null} or empty {@code contentType} — the client may have
 *                 omitted the header, and the registry may still route on {@code targetType}
 *                 alone;</li>
 *             <li>MUST wrap binding-specific exceptions (a Jackson {@code JacksonException}, say)
 *                 before returning, so no driver type crosses the SPI boundary — and the wrapper is
 *                 not free choice, because ADR-036 §2 puts status mapping on the handler and a
 *                 handler cannot map what it cannot tell apart:
 *                 <ul>
 *                   <li>a body the decoder cannot bind (malformed syntax, wrong shape) MUST surface
 *                       as {@link eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException}
 *                       ({@code EX-HTTP-4013}) — a caller fault, {@code 400 Bad Request};</li>
 *                   <li>a missing or unresolvable decoder stays an {@link IllegalStateException} —
 *                       a deployment fault, {@code 5xx}, never downgraded to 400.</li>
 *                 </ul>
 *             </li>
 *           </ul>
 * @since 0.8
 */
public interface HttpRequestBodyDecoder {

    /**
     * Returns true when this decoder can decode the given target type from the
     * given content type.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType request {@code content-type} header value, or {@code null}/empty
     * @return true when this decoder can produce {@code targetType} from a body of {@code contentType}
     * @implSpec Answer without throwing for a {@code null} or empty {@code contentType}: the client
     *           may have omitted the header, and a decoder that claims {@code targetType} on its
     *           own is still a legitimate answer.
     */
    boolean supports(Class<?> targetType, String contentType);

    /**
     * Decodes the off-heap request body into a typed payload instance.
     *
     * <p>The returned value is the decoder's typed representation of {@code body};
     * the cast to the caller's static {@code <T>} is performed at the resolution
     * call site (the generated handler).
     *
     * @param body       request body buffer; never null (the caller short-circuits null/empty cases as documented)
     * @param targetType desired payload runtime class; never null
     * @param context    decoding context (method, path, headers, allocator); never null
     * @return decoded payload instance, or {@code null} when the body is empty and the implementation tolerates it
     * @throws eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException
     *         ({@code EX-HTTP-4013}) if the bytes are malformed for this binding or do not bind to
     *         {@code targetType} — a caller fault the handler answers with {@code 400}
     * @implSpec Do not close or retain {@code body}; the caller owns its lifecycle. Carry the
     *           binding's own failure as the cause rather than its message, and never quote the
     *           body — a malformed payload is exactly the content most likely to be a secret sent
     *           to the wrong endpoint.
     */
    Object decode(LoanedBuffer body, Class<?> targetType, HttpRequestDecodingContext context);

    /**
     * Decoder priority. Higher value wins when multiple decoders support a
     * {@code (targetType, contentType)} pair.
     *
     * @return priority, default 0
     * @implSpec The default implementation returns {@code 0}. A decoder meant to displace a
     *           provider's shipped default for the same pair returns a higher value; equal values
     *           leave registration order to break the tie.
     */
    default int priority() {
        return 0;
    }
}
