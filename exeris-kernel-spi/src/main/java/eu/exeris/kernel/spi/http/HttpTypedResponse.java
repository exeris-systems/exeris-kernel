/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import java.util.List;
import java.util.Objects;

/**
 * SPI: Typed response descriptor for additive auto-binding response encoding.
 *
 * <p>This carrier allows handlers to return a domain payload while the engine/provider
 * encodes it to {@link HttpResponse} via a configured encoder registry.
 *
 * @param status response status; non-null
 * @param headers additional response headers; non-null, may be empty
 * @param payload typed payload object; may be null
 * @since 0.5.0
 */
public record HttpTypedResponse(
        HttpStatus status,
        List<HttpHeader> headers,
        Object payload
) {

    public HttpTypedResponse {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Creates typed response with no extra headers.
     *
     * @param status response status
     * @param payload payload object
     * @return typed response descriptor
     */
    @SuppressWarnings("PMD.ShortMethodName")
    public static HttpTypedResponse of(HttpStatus status, Object payload) {
        return new HttpTypedResponse(status, List.of(), payload);
    }
}
