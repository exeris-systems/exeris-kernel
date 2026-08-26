/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

/**
 * SPI: A half-open byte range {@code [offset, offset + length)} within an object.
 *
 * <p>Expressed as offset-plus-length rather than as inclusive first/last byte positions, because the
 * inclusive form (which HTTP {@code Range} uses on the wire) makes an empty range unrepresentable and a
 * one-byte range easy to get wrong. Translating to the wire form is the driver's job.
 *
 * @param offset zero-based first byte to read; never negative
 * @param length number of bytes to read; strictly positive
 * @since 0.11.0
 */
public record BlobRange(long offset, long length) {

    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if {@code offset} is negative, {@code length} is not positive,
     *                                  or the range end overflows
     */
    public BlobRange {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        if (offset > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("range end overflows");
        }
    }

    /**
     * Returns the exclusive end offset of this range.
     *
     * @return {@code offset + length}
     */
    public long endExclusive() {
        return offset + length;
    }
}
