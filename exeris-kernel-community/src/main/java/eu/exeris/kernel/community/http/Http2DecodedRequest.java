/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.List;

/**
 * One decoded HTTP/2 request, assembled from a completed HEADERS(+CONTINUATION) block.
 *
 * @param streamId the HTTP/2 stream id this request was decoded from
 * @param method   the request method decoded from the {@code :method} pseudo-header, or
 *                 {@code null} when it was absent or unrecognised
 * @param path     the request target decoded from the {@code :path} pseudo-header, or the empty
 *                 string when absent
 * @param headers  the regular (non-pseudo) headers, in the order they appeared on the wire
 * @param valid    {@code false} when the block violated RFC 7540 §8.1.2's pseudo-header rules or
 *                 HPACK decoding failed; a caller must respond {@code 400} rather than rely on the
 *                 other fields, which may still carry partially-decoded values
 */
record Http2DecodedRequest(int streamId,
                           HttpMethod method,
                           String path,
                           List<HttpHeader> headers,
                           boolean valid) {
}
