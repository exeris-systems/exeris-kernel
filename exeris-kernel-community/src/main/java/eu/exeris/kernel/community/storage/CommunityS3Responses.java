/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;

import java.util.Optional;

/**
 * Reading an S3 response: the four questions the store asks of one.
 *
 * <p>Separate from {@link CommunityS3Client} because that class decides how a request is made and this
 * one decides what came back. Both are small; keeping them apart is what stops the store from growing
 * header parsing.
 *
 * @since 0.11
 */
final class CommunityS3Responses {

    /** Header carrying the object size on a {@code HEAD} or {@code GET}. */
    /* default */ static final String HEADER_CONTENT_LENGTH = "Content-Length";

    /** Header carrying the declared media type. */
    /* default */ static final String HEADER_CONTENT_TYPE = "Content-Type";

    /** Returned by {@link #sizeOf} when the response does not say how large the object is. */
    /* default */ static final long SIZE_UNDECLARED = -1L;

    private CommunityS3Responses() {
        // Utility holder — not instantiable.
    }

    /**
     * Returns the first value of a response header, matched case-insensitively.
     *
     * @param response the response to read
     * @param name     the header name
     * @return the value, or empty when the header is absent
     */
    /* default */ static Optional<String> header(HttpResponse response, String name) {
        for (HttpHeader candidate : response.headers()) {
            if (candidate.nameEqualsIgnoreCase(name)) {
                return Optional.of(candidate.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the object size a response reports, or {@link #SIZE_UNDECLARED} if it does not.
     *
     * <p>This used to answer {@code 0} for a missing or unparseable {@code Content-Length},
     * reasoning that refusing to describe an object over one malformed header turns a cosmetic fault
     * at the store into an outage. That holds for {@code stat}, where an unknown size is a degraded
     * description of an object the caller still learns exists. It does not hold for {@code download},
     * which is the other consumer of this HEAD: there, {@code 0} does not read as "size unknown", it
     * reads as "no bytes to fetch" — the range comes out empty, the download completes, and the
     * caller is handed an empty object that is not empty. A successful wrong answer, not an outage
     * avoided.
     *
     * <p>So the two cases are separated here and the caller decides. Distinguishing them is the whole
     * change; nothing about the {@code 0}-versus-absent question can be recovered downstream once
     * both have been flattened into a {@code long}.
     *
     * @param response the response to read
     * @return the size in bytes, or {@link #SIZE_UNDECLARED} when absent or malformed
     */
    /* default */ static long sizeOf(HttpResponse response) {
        Optional<String> declared = header(response, HEADER_CONTENT_LENGTH);
        if (declared.isEmpty()) {
            return SIZE_UNDECLARED;
        }
        try {
            long parsed = Long.parseLong(declared.get().strip());
            // A negative length is undeclared, not zero. Clamping it to 0 — which is what this did —
            // put it back on the exact path the sentinel was added to close: 0 is a legitimate size,
            // so it passes the undeclared test and download() hands back an empty handle for an
            // object that is not empty. The clamp cannot simply be dropped either, since -1 is now
            // the sentinel and would read as "undeclared" by accident rather than by decision.
            return parsed < 0 ? SIZE_UNDECLARED : parsed;
        } catch (NumberFormatException e) {
            return SIZE_UNDECLARED;
        }
    }

    /**
     * Returns the media type a response declares, falling back to the SPI default.
     *
     * @param response the response to read
     * @return the declared content type, never {@code null}
     */
    /* default */ static String contentTypeOf(HttpResponse response) {
        return header(response, HEADER_CONTENT_TYPE)
                .filter(value -> !value.isBlank())
                .orElse(BlobMetadata.DEFAULT_CONTENT_TYPE);
    }

    /**
     * Returns whether a status is one this driver treats as success.
     *
     * @param response the response to inspect
     * @return {@code true} for {@code 200}, {@code 204} and {@code 206}
     */
    /* default */ static boolean isSuccess(HttpResponse response) {
        int code = response.status().code();
        return code == HttpStatus.OK.code()
                || code == HttpStatus.NO_CONTENT.code()
                || code == HttpStatus.PARTIAL_CONTENT.code();
    }

    /**
     * Releases a response body if there is one.
     *
     * <p>Every response the driver does not hand to a download handle passes through here. The buffer
     * comes from the HTTP engine's pool, so dropping it leaks a pooled segment.
     *
     * @param response the response to release
     */
    /* default */ static void closeBody(HttpResponse response) {
        if (response.hasBody()) {
            response.body().close();
        }
    }
}
