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
 * <p>The façade short-circuits {@code Void.class} before calling a decoder, so a decoder never sees
 * it.
 *
 * <p><b>Allocation:</b> allocates (the decoded payload {@link #decode} returns); the body is read
 * from the loaned segment in place, and no decoder is given a heap copy of it
 * <p><b>Ownership:</b> the decoder owns nothing — the façade releases the response body buffer once
 * decoding completes, on the failure path as well
 *
 * @implSpec An implementation:
 *           <ul>
 *             <li>MUST NOT close, retain, or otherwise extend the lifetime of the
 *                 {@link LoanedBuffer} it is handed;</li>
 *             <li>MUST tolerate a {@code null} or empty {@code contentType} — the server may have
 *                 omitted the header, and the registry may still route on {@code targetType}
 *                 alone;</li>
 *             <li>MUST wrap binding-specific exceptions (a Jackson {@code JacksonException}, say)
 *                 in a generic {@link RuntimeException} — typically
 *                 {@link IllegalStateException} — so no driver type crosses the SPI boundary;</li>
 *             <li>MAY return {@code null} for an empty body when {@code targetType} is nullable;
 *                 the façade decides per call whether {@code null} is an acceptable answer.</li>
 *           </ul>
 * @since 0.8
 */
public interface HttpResponseBodyDecoder {

    /**
     * Returns true when this decoder can decode the given target type from the
     * given content type.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType response {@code content-type} header value, or {@code null}/empty
     * @return true when this decoder can produce {@code targetType} from a body of {@code contentType}
     * @implSpec Answer without throwing for a {@code null} or empty {@code contentType}: the server
     *           may have omitted the header, and a decoder that claims {@code targetType} on its
     *           own is still a legitimate answer.
     */
    boolean supports(Class<?> targetType, String contentType);

    /**
     * Decodes the off-heap response body into a typed payload instance.
     *
     * <p>The returned value is the decoder's typed representation of {@code body};
     * the cast to the caller's static {@code <T>} is performed at the façade
     * call site.
     *
     * @param body       response body buffer; never null (the façade short-circuits null/empty cases as documented)
     * @param targetType desired payload runtime class; never null
     * @param context    decoding context (status, headers, allocator); never null
     * @return decoded payload instance, or {@code null} when the body is empty and the implementation tolerates it
     * @implSpec Do not close or retain {@code body}; the façade owns its lifecycle and releases it
     *           after this call returns or throws.
     */
    Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context);

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
