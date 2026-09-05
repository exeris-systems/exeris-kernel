/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Objects;

/**
 * SPI: Encoded response body produced by {@link HttpResponseBodyEncoder}.
 *
 * @param headers headers generated during encoding; non-null, may be empty
 * @param body encoded body buffer; nullable for bodyless responses
 * @since 0.5
 */
public record HttpEncodedBody(
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    public HttpEncodedBody {
        Objects.requireNonNull(headers, "headers must not be null");
    }

    /**
     * Creates bodyless encoded response.
     *
     * @return bodyless encoded representation
     */
    public static HttpEncodedBody noBody() {
        return new HttpEncodedBody(List.of(), null);
    }
}
