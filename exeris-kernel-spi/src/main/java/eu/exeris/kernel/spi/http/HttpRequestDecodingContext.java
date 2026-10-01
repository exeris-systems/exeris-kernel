/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>The allocator is optional, and a decoder must not assume one</h2>
 * <p>{@code allocator} is {@code null} when the caller has none to offer, and a decoder that
 * dereferences it unconditionally is broken rather than unlucky. Requiring one would couple every
 * request-decode site to a bound
 * {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR}, including sites whose
 * decoder never touches it — and an unbound slot would then surface inside a handler as a failure
 * to build the <em>context</em>, which reads as a malformed body rather than as missing wiring.
 *
 * <p>The allocator earns its place on the <em>encoding</em> contexts, where an encoder must produce
 * an off-heap body; a decoder is handed a {@link eu.exeris.kernel.spi.memory.LoanedBuffer} that is
 * already allocated. A decoder that genuinely needs auxiliary off-heap memory takes an allocator at
 * construction, the way it takes its object mapper — a per-request context is the wrong place to
 * carry a per-decoder dependency.
 *
 * @param method    request method (e.g., {@code POST}, {@code PUT}); non-null
 * @param path      request path (e.g., {@code /widgets}); non-null
 * @param headers   immutable request headers; non-null, may be empty
 * @param allocator allocator for any auxiliary off-heap buffers a decoder may need; may be
 *                  {@code null} — see above
 * @since 0.8
 */
public record HttpRequestDecodingContext(
        HttpMethod method,
        String path,
        List<HttpHeader> headers,
        MemoryAllocator allocator
) {

    /**
     * Rejects the three components a decoder is entitled to read unconditionally; {@code allocator}
     * stays nullable, which is the whole point of the field.
     *
     * @throws NullPointerException if {@code method}, {@code path} or {@code headers} is
     *                              {@code null}
     */
    public HttpRequestDecodingContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Builds a context with no allocator — the shape every decoder shipped with the kernel needs.
     *
     * @param method  request method; non-null
     * @param path    request path; non-null
     * @param headers immutable request headers; non-null, may be empty
     * @since 0.12
     */
    public HttpRequestDecodingContext(HttpMethod method, String path, List<HttpHeader> headers) {
        this(method, path, headers, null);
    }
}
