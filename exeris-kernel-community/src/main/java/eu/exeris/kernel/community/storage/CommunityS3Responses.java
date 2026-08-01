/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.11.0
 */
final class CommunityS3Responses {

    /** Header carrying the object size on a {@code HEAD} or {@code GET}. */
    /* default */ static final String HEADER_CONTENT_LENGTH = "Content-Length";

    /** Header carrying the declared media type. */
    /* default */ static final String HEADER_CONTENT_TYPE = "Content-Type";

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
     * Returns the object size a response reports.
     *
     * <p>A missing or unparseable {@code Content-Length} reads as zero rather than as a failure. The
     * store has already established that the object exists; refusing to describe it because one header
     * was malformed would turn a cosmetic fault at the store into an outage for the caller.
     *
     * @param response the response to read
     * @return the size in bytes, or {@code 0} when the header is absent or malformed
     */
    /* default */ static long sizeOf(HttpResponse response) {
        Optional<String> declared = header(response, HEADER_CONTENT_LENGTH);
        if (declared.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(declared.get().strip()));
        } catch (NumberFormatException e) {
            return 0L;
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
