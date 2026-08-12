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
 * SPI: Response encoding context passed to typed response body encoders.
 *
 * @param request inbound request associated with the exchange; non-null
 * @param allocator allocator for off-heap response buffers; non-null
 * @since 0.5.0
 */
public value record HttpResponseEncodingContext(
        HttpRequest request,
        MemoryAllocator allocator
) {

    public HttpResponseEncodingContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}
