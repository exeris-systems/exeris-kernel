/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.List;

record ReadResult(HttpMethod method,
                  String path,
                  HttpVersion version,
                  List<HttpHeader> headers,
                  long bodyStart,
                  int bodyLength,
                  long consumedBytes,
                  boolean keepAlive) {
}
