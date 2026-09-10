/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.util.Objects;

/**
 * SPI: Request encoding context passed to typed request body encoders on the client side.
 *
 * <p>Deliberately carries only {@code (method, path, allocator)} — request headers are
 * <em>not</em> part of the context because the encoder runs <strong>before</strong>
 * the request carrier exists. The encoder is responsible only for producing body bytes
 * plus the {@code content-type} it owns (returned via {@link HttpEncodedBody#headers()}).
 * Headers established by the encoder are merged with façade-owned headers
 * ({@code accept}, {@code content-length}) and enricher-added headers at the call site.
 * This asymmetry vs server-side {@link HttpResponseEncodingContext#request()} is
 * intentional — see ADR-034 §3.
 *
 * @param method    HTTP method for the outbound request; non-null
 * @param path      request-target path component; non-null
 * @param allocator allocator for off-heap request buffers; non-null
 * @since 0.8
 */
public record HttpRequestEncodingContext(
        HttpMethod method,
        String path,
        MemoryAllocator allocator
) {

    /**
     * Rejects every component, the allocator included: an encoder's job is to produce off-heap
     * bytes, so a context without one describes work that cannot be done.
     *
     * @throws NullPointerException if {@code method}, {@code path} or {@code allocator} is
     *                              {@code null}
     */
    public HttpRequestEncodingContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
