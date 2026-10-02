/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Registry contract for typed response body encoder resolution.
 *
 * <p>The seam an exchange consults to turn a handler's domain payload into response bytes: it
 * answers with the encoder to use for one payload type, or with {@code null} to say the deployment
 * registered none.
 *
 * @implSpec Return {@code null} rather than a fallback encoder when nothing supports the payload
 *           type: the caller answers an unencodable payload with a server-side error, and an
 *           encoder chosen at random would put a body of the wrong media type on the wire instead.
 * @since 0.5
 */
@FunctionalInterface
public interface HttpResponseBodyEncoderRegistry {

    /**
     * Resolves an encoder for payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return matching encoder when available, otherwise {@code null}
     */
    HttpResponseBodyEncoder resolve(Class<?> payloadType);

    /**
     * Returns a registry that resolves no encoders.
     *
     * @return empty registry
     */
    static HttpResponseBodyEncoderRegistry empty() {
        return payloadType -> null;
    }
}
