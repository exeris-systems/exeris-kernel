/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
