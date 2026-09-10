/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.storage.blob.BlobRange;

/**
 * How much of an object a read actually covers, once its size is known.
 *
 * <p>Its own type because the arithmetic has three rules that are easy to get wrong and impossible to
 * see once they are inlined into a download path: a whole-object read covers everything, a range past
 * the end truncates to what exists, and a range starting at or beyond the end covers nothing at all.
 * The last one is a contract requirement rather than an edge case — a caller walking an object with a
 * fixed window must not need the size to avoid an exception on its final window (ADR-056 §3).
 *
 * @param offset first byte to read
 * @param length number of bytes covered; {@code 0} when the range lies at or past the end
 * @since 0.11
 */
/* default */ record CommunityS3Slice(long offset, long length) {

    /**
     * Resolves a requested range against the object that exists.
     *
     * @param range      the requested range, or {@code null} for the whole object
     * @param objectSize the object's size, as the store reported it
     * @return the slice actually covered; never {@code null}
     */
    /* default */ static CommunityS3Slice covering(BlobRange range, long objectSize) {
        if (range == null) {
            return new CommunityS3Slice(0L, objectSize);
        }
        long available = Math.max(0L, objectSize - range.offset());
        return new CommunityS3Slice(range.offset(), Math.min(range.length(), available));
    }

    /** Returns the inclusive-end form HTTP uses on the wire. */
    /* default */ String rangeHeaderValue() {
        return "bytes=" + offset + "-" + (offset + length - 1);
    }

    /** Returns whether this slice covers any bytes at all. */
    /* default */ boolean isEmpty() {
        return length <= 0;
    }
}
