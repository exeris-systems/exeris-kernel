/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Registry contract for typed response body encoder resolution.
 *
 * @since 0.5.0
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
