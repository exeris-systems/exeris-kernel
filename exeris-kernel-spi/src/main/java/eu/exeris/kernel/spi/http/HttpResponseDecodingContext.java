/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>{@code allocator} is optional and a decoder must not assume one, for the reasons set out on
 * {@link HttpRequestDecodingContext} — this is the client-side mirror and the rule is the same on
 * both sides, so that a decoder written against one context behaves the same against the other.
 *
 * @param status    wire status code (e.g., 200, 404, 500)
 * @param headers   immutable response headers; non-null, may be empty
 * @param allocator allocator for any auxiliary off-heap buffers a decoder may need; may be
 *                  {@code null}
 * @since 0.8
 */
public record HttpResponseDecodingContext(
        int status,
        List<HttpHeader> headers,
        MemoryAllocator allocator
) {

    public HttpResponseDecodingContext {
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Builds a context with no allocator — the shape every decoder shipped with the kernel needs.
     *
     * @param status  wire status code
     * @param headers immutable response headers; non-null, may be empty
     * @since 0.12
     */
    public HttpResponseDecodingContext(int status, List<HttpHeader> headers) {
        this(status, headers, null);
    }
}
