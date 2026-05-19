/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.util.List;
import java.util.Objects;

/**
 * SPI: Response decoding context passed to typed response body decoders on the client side.
 *
 * <p>Carries the wire status code, response headers (immutable snapshot), and the
 * kernel memory allocator. Decoders may inspect headers for content-type details,
 * but the registry has already matched on {@code (targetType, contentType)} before
 * {@link HttpResponseBodyDecoder#decode} is invoked.
 *
 * @param status    wire status code (e.g., 200, 404, 500)
 * @param headers   immutable response headers; non-null, may be empty
 * @param allocator allocator for any auxiliary off-heap buffers a decoder may need; non-null
 * @since 0.8.0
 */
public record HttpResponseDecodingContext(
        int status,
        List<HttpHeader> headers,
        MemoryAllocator allocator
) {

    public HttpResponseDecodingContext {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
