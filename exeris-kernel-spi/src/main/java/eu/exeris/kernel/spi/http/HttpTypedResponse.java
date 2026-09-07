/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.List;
import java.util.Objects;

/**
 * SPI: a response stated as a domain payload, for the engine to encode.
 *
 * <p>The carrier a handler answers with when it wants the response body produced by the configured
 * {@link HttpResponseBodyEncoderRegistry} rather than assembled by hand: the handler names the
 * status, any headers of its own and the object, and the engine resolves an encoder, allocates the
 * body and owns the buffer from there.
 *
 * <p>It holds no buffer itself, so an unanswered {@code HttpTypedResponse} leaks nothing.
 *
 * @param status response status; non-null
 * @param headers additional response headers, merged with those the encoder establishes; non-null,
 *                may be empty
 * @param payload typed payload object; may be null, which encodes to a bodyless response
 * @since 0.5
 */
public record HttpTypedResponse(
        HttpStatus status,
        List<HttpHeader> headers,
        Object payload
) {

    /**
     * Rejects a null status or header list; {@code payload} stays nullable, because that is how a
     * typed answer says "this status, no body".
     *
     * @throws NullPointerException if {@code status} or {@code headers} is {@code null}
     */
    public HttpTypedResponse {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Returns a typed response carrying only what the encoder needs — the status and the payload,
     * with no headers of the handler's own.
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
