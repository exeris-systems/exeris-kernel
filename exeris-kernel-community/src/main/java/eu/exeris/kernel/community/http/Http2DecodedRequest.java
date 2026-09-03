/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.List;

record Http2DecodedRequest(int streamId,
                           HttpMethod method,
                           String path,
                           List<HttpHeader> headers,
                           boolean valid) {
}
