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
 * @since 0.8.0
 */
public value record HttpRequestEncodingContext(
        HttpMethod method,
        String path,
        MemoryAllocator allocator
) {

    public HttpRequestEncodingContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
