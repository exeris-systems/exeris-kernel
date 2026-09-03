/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Neutral response body encoder contract for typed auto-binding responses.
 *
 * <p>Implementations are provider-side and transport-agnostic: they map domain payloads
 * to an off-heap encoded representation that can be wrapped in {@link HttpResponse}.
 *
 * @since 0.5.0
 */
public interface HttpResponseBodyEncoder {

    /**
     * Returns true when this encoder can encode the given payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return true if this encoder supports the payload type
     */
    boolean supports(Class<?> payloadType);

    /**
     * Encodes payload into an off-heap response body and optional headers.
     *
     * @param payload payload object; may be null
     * @param context encoding context; never null
     * @return encoded response body descriptor; never null
     */
    HttpEncodedBody encode(Object payload, HttpResponseEncodingContext context);

    /**
     * Encoder priority. Higher value wins when multiple encoders support a type.
     *
     * @return priority, default 0
     */
    default int priority() {
        return 0;
    }
}
