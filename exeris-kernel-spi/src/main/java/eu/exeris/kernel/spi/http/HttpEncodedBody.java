/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Objects;

/**
 * SPI: the output of one body encode — the off-heap bytes plus the headers the encoder owns.
 *
 * <p>Produced by {@link HttpResponseBodyEncoder} on the server side and by
 * {@link HttpRequestBodyEncoder} on the client side; the two share this carrier so an encoder pair
 * for one media type describes both directions the same way. The headers are the ones the encoding
 * itself establishes (typically {@code content-type}); they are merged with the caller's own headers
 * at the call site, never applied by this record.
 *
 * <p><b>Allocation:</b> allocates (the encoder allocates {@link #body()} off-heap through the
 * encoding context's {@code MemoryAllocator}; {@link #noBody()} allocates no buffer at all)
 * <p><b>Ownership:</b> the buffer travels with the carrier — ownership passes to the
 * {@link HttpRequest} or {@link HttpResponse} that carries it, and the engine releases it once the
 * write completes on the response direction ({@link HttpExchange#respond(HttpResponse)}); this
 * interface does not establish who releases it on the request/client-send direction. An encoder
 * must not close or retain the buffer after returning.
 *
 * @param headers headers generated during encoding; non-null, may be empty
 * @param body encoded body buffer, or {@code null} for a bodyless response
 * @implNote The Community client engine does not release the buffer on the request/client-send
 *           direction after the write completes.
 * @since 0.5
 */
public record HttpEncodedBody(
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    /**
     * Rejects a null header list; a {@code null} {@link #body()} stays legal, because that is how a
     * bodyless response is expressed.
     *
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    public HttpEncodedBody {
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Returns the carrier for an encode that produced no bytes — an empty header list and a
     * {@code null} buffer, so there is nothing for the engine to release.
     *
     * @return bodyless encoded representation
     */
    public static HttpEncodedBody noBody() {
        return new HttpEncodedBody(List.of(), null);
    }
}
