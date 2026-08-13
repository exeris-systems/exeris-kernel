/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.List;

value record ReadResult(HttpMethod method,
                  String path,
                  HttpVersion version,
                  List<HttpHeader> headers,
                  long bodyStart,
                  int bodyLength,
                  long consumedBytes,
                  boolean keepAlive) {
}
