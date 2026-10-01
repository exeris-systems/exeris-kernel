/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.List;

/**
 * One successfully parsed HTTP/1.x request: the request line and headers, plus enough framing
 * information for the caller to locate and later discard the body bytes in the aggregate buffer
 * they were parsed from.
 *
 * @param method        the parsed request method
 * @param path          the request-target exactly as sent on the wire
 * @param version       the parsed protocol version
 * @param headers       the parsed headers, in wire order, as an unmodifiable view
 * @param bodyStart     offset of the first body byte within the buffer that was parsed
 * @param bodyLength    the body length in bytes, resolved from the request's framing header
 * @param consumedBytes total bytes this request occupies in the buffer, request line through body
 * @param keepAlive     whether the connection stays open for a subsequent request on the same buffer
 */
record ReadResult(HttpMethod method,
                  String path,
                  HttpVersion version,
                  List<HttpHeader> headers,
                  long bodyStart,
                  int bodyLength,
                  long consumedBytes,
                  boolean keepAlive) {
}
