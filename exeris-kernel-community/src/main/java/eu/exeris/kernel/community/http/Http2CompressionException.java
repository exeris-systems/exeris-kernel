/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDecoder;

/**
 * A header block that could not be decoded: the HPACK decoder state, which every stream on the
 * connection shares, can no longer be trusted, so RFC 9113 §4.3 makes it a connection error of
 * type {@code COMPRESSION_ERROR} rather than a failure of the one stream.
 *
 * <p>Distinct from a decode failure of kind
 * {@link HpackDecoder.HpackDecodingException.Kind#SIZE_LIMIT_EXCEEDED}, which leaves the decoder
 * in step with the peer and so is answered on the stream alone.
 */
final class Http2CompressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /* default */ Http2CompressionException(Throwable cause) {
        super("HTTP/2 header block could not be decoded", cause);
    }
}
