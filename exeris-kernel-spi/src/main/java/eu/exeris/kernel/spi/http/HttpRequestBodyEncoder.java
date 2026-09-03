/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Neutral request body encoder contract for typed outbound HTTP request bodies.
 *
 * <p>Implementations are provider-side and transport-agnostic: they marshal a domain
 * payload to an off-heap encoded body that can be wrapped in {@link HttpRequest}.
 * The mirror of the server-side {@link HttpResponseBodyEncoder} (since 0.5.0); both
 * share the {@link HttpEncodedBody} carrier shape.
 *
 * <h2>Context-locking</h2>
 * <p>Implementations MUST NOT depend on request headers. The
 * {@link HttpRequestEncodingContext} deliberately carries only
 * {@code (method, path, allocator)} because the encoder runs <em>before</em> header
 * finalisation — the encoder's job is to produce body bytes plus the
 * {@code content-type} it owns (returned via {@link HttpEncodedBody#headers()}).
 * Headers produced by the encoder are merged with façade-owned headers
 * ({@code accept}, {@code content-length}) and enricher-added headers at the call site.
 *
 * <h2>Driver exception wrapping</h2>
 * <p>Implementations MUST wrap binding-specific exceptions (e.g., Jackson
 * {@code JacksonException}) into a generic {@link RuntimeException} (typically
 * {@link IllegalStateException}) before returning. No driver-specific exception
 * types may cross the SPI boundary.
 *
 * @since 0.8.0
 */
public interface HttpRequestBodyEncoder {

    /**
     * Returns true when this encoder can encode the given payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return true if this encoder supports the payload type
     */
    boolean supports(Class<?> payloadType);

    /**
     * Encodes payload into an off-heap request body plus the {@code content-type}
     * header(s) the encoder owns.
     *
     * <p>Buffer ownership transfers from the returned {@link HttpEncodedBody} to the
     * {@link HttpRequest} that carries it; callers must not close the buffer after
     * passing it to a request.
     *
     * @param payload payload object; non-null (the registry filters non-supports cases out)
     * @param context encoding context; never null
     * @return encoded request body descriptor; never null
     */
    HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context);

    /**
     * Encoder priority. Higher value wins when multiple encoders support a type.
     *
     * @return priority, default 0
     */
    default int priority() {
        return 0;
    }
}
