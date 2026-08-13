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
 * SPI: Request decoding context passed to typed request body decoders on the server side.
 *
 * <p>Carries the request method, the request path, the request headers (immutable
 * snapshot), and the kernel memory allocator. Decoders may inspect headers for
 * content-type details, but the registry has already matched on
 * {@code (targetType, contentType)} before {@link HttpRequestBodyDecoder#decode}
 * is invoked.
 *
 * <p>The server-side mirror of {@link HttpResponseDecodingContext}'s
 * {@code (status, headers, allocator)}: the response {@code status} field is
 * replaced by {@code method} + {@code path} because the decoder runs on the
 * inbound request, not on a response. The context carries no Core types — no
 * {@code HttpExchange}, no router carrier — so the SPI surface stays
 * implementation-blind (The Wall, ADR-006).
 *
 * @param method    request method (e.g., {@code POST}, {@code PUT}); non-null
 * @param path      request path (e.g., {@code /widgets}); non-null
 * @param headers   immutable request headers; non-null, may be empty
 * @param allocator allocator for any auxiliary off-heap buffers a decoder may need; non-null
 * @since 0.8.0
 */
public value record HttpRequestDecodingContext(
        HttpMethod method,
        String path,
        List<HttpHeader> headers,
        MemoryAllocator allocator
) {

    public HttpRequestDecodingContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
