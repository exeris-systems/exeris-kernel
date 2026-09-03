/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

import java.util.Objects;

/**
 * SPI: What the kernel knows about a stored object.
 *
 * <p>Deliberately three fields. Vendor metadata — storage class, ETag, server-side encryption state,
 * user-defined tags — is driver territory; carrying it here would put one vendor's model in the contract
 * and oblige every other driver to fake it.
 *
 * @param ref         the object this describes
 * @param sizeBytes   object size in bytes; never negative
 * @param contentType the declared media type, or {@code "application/octet-stream"} when none was given
 * @since 0.11.0
 */
public record BlobMetadata(BlobRef ref, long sizeBytes, String contentType) {

    /** The media type assumed when a caller declares none. */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if {@code ref} or {@code contentType} is {@code null}
     * @throws IllegalArgumentException if {@code sizeBytes} is negative
     */
    public BlobMetadata {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
