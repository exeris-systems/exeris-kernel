/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.List;

/**
 * The answer to a request the developer-licence gate refused: {@code 429 Too Many Requests},
 * {@code Retry-After: 1} and no body. One shape for every protocol, so HTTP/1.1 and HTTP/2 refuse
 * identically and the client's retry rule is the same on both.
 */
final class CommunityHttpThrottle {

    private static final List<HttpHeader> HEADERS = List.of(new HttpHeader("Retry-After", "1"));

    private CommunityHttpThrottle() {
    }

    /**
     * Builds the refusal response.
     *
     * @param version the version the refused request arrived on
     * @return the response a driver writes in place of dispatching the request
     */
    /* default */ static HttpResponse response(HttpVersion version) {
        return HttpResponse.noBody(HttpStatus.TOO_MANY_REQUESTS, version, HEADERS);
    }
}
