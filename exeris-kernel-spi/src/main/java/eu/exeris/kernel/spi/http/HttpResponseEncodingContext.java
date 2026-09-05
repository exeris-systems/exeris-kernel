/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.util.Objects;

/**
 * SPI: Response encoding context passed to typed response body encoders.
 *
 * @param request inbound request associated with the exchange; non-null
 * @param allocator allocator for off-heap response buffers; non-null
 * @since 0.5
 */
public record HttpResponseEncodingContext(
        HttpRequest request,
        MemoryAllocator allocator
) {

    public HttpResponseEncodingContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
