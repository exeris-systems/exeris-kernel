/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

/**
 * RFC 7541 §4 — HPACK Dynamic Table with FIFO eviction.
 *
 * <h2>Contract</h2>
 * <p>The dynamic table is a bounded, first-in-first-out table of header field entries.
 * Each entry has an RFC-defined overhead of 32 bytes (§4.1) added to the sum of the
 * name and value byte lengths.
 *
 * <h2>Index Address Space</h2>
 * <p>Dynamic table indices start at {@code HpackStaticTable.SIZE + 1}. The newest
 * entry has the lowest dynamic index. This class uses a circular buffer internally;
 * callers address entries via absolute combined index (static + dynamic).
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each HTTP/2 connection owns its own encoder/decoder context
 * with independent dynamic tables (RFC 7541 §2.2).
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#section-4">RFC 7541 §4</a>
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public final class HpackDynamicTable {

    private static final int ENTRY_OVERHEAD   = 32;
    private static final int INITIAL_CAPACITY = 64;
    private static final long MIN_TABLE_SIZE  = 0L;

    private Entry[] entries;
    private int head;
    private int count;
    private long currentSize;
    private long maxSize;

    /**
     * Valhalla-Ready: Will be migrated to {@code value record} once JEP 401 is mainline.
     * Currently relies on C2 JIT Escape Analysis for scalarization on hot-paths.
     */
    private record Entry(String name, String value) {
        /* package */ long byteSize() {
            return HpackUtf8.byteLength(name)
                    + HpackUtf8.byteLength(value)
                    + (long) ENTRY_OVERHEAD;
        }
    }

    /**
     * Creates a dynamic table with the given maximum size in bytes.
     *
     * @param maxTableSize maximum size in bytes (per SETTINGS_HEADER_TABLE_SIZE)
     */
    public HpackDynamicTable(long maxTableSize) {
        if (maxTableSize < MIN_TABLE_SIZE) {
            throw new IllegalArgumentException(
                    "maxTableSize must be >= 0, got: " + maxTableSize);
        }
        this.maxSize = maxTableSize;
        this.entries = new Entry[INITIAL_CAPACITY];
    }

    /**
     * Returns the number of entries currently in the dynamic table.
     *
     * @return entry count
     */
    public int size() {
        return count;
    }

    /**
     * Returns the current byte size of the dynamic table (sum of entry sizes).
     *
     * @return byte size
     */
    public long byteSize() {
        return currentSize;
    }

    /**
     * Returns the header name at the given 0-based dynamic table index.
     * Index 0 = newest entry.
     *
     * @param index 0-based dynamic index
     * @return header name
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public String getName(int index) {
        checkIndex(index);
        return entries[mapIndex(index)].name();
    }

    /**
     * Returns the header value at the given 0-based dynamic table index.
     * Index 0 = newest entry.
     *
     * @param index 0-based dynamic index
     * @return header value
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public String getValue(int index) {
        checkIndex(index);
        return entries[mapIndex(index)].value();
    }

    /**
     * Inserts a new entry at the beginning of the dynamic table.
     * Evicts oldest entries as needed to satisfy the size constraint (§4.4).
     *
     * @param name  header field name
     * @param value header field value
     */
    public void add(String name, String value) {
        Entry entry = new Entry(name, value);
        long entrySize = entry.byteSize();

        while (currentSize + entrySize > maxSize && count > 0) {
            evictOldest();
        }

        if (entrySize > maxSize) {
            clear();
            return;
        }

        if (count == entries.length) {
            grow();
        }

        head = (head - 1 + entries.length) % entries.length;
        entries[head] = entry;
        count++;
        currentSize += entrySize;
    }

    /**
     * Updates the maximum table size. Evicts entries as needed (§4.3).
     *
     * @param newMaxSize new maximum size in bytes
     */
    public void setMaxSize(long newMaxSize) {
        if (newMaxSize < MIN_TABLE_SIZE) {
            throw new IllegalArgumentException(
                    "newMaxSize must be >= 0, got: " + newMaxSize);
        }
        this.maxSize = newMaxSize;
        while (currentSize > maxSize && count > 0) {
            evictOldest();
        }
    }

    /**
     * Returns the maximum table size in bytes.
     *
     * @return max size
     */
    public long maxSize() {
        return maxSize;
    }

    /**
     * Finds the best matching entry across the combined index address space.
     *
     * <p>Returns a packed result: low 16 bits = combined 1-based index,
     * bit 16 = full match flag. Returns 0 if no match found.
     *
     * <p>Priority: static full match &gt; dynamic full match &gt; dynamic name-only
     * match &gt; static name-only match. Dynamic entries are preferred for name-only
     * fallback because they contain recently added headers and yield better
     * incremental-indexing compression for repeated custom header names.
     *
     * @param name  header field name
     * @param value header field value
     * @return packed result, or 0 if not found
     */
    public int find(String name, String value) {
        int staticResult = HpackStaticTable.find(name, value);
        if ((staticResult & 0x100) != 0) {
            return 0x10000 | (staticResult & 0xFF);
        }

        int staticNameOnly = staticResult & 0xFF;
        int dynamicNameOnly = 0;

        for (int idx = 0; idx < count; idx++) {
            int pos = mapIndex(idx);
            Entry entry = entries[pos];
            if (entry.name().equals(name)) {
                int combinedIndex = HpackStaticTable.SIZE + 1 + idx;
                if (entry.value().equals(value)) {
                    return 0x10000 | combinedIndex;
                }
                if (dynamicNameOnly == 0) {
                    dynamicNameOnly = combinedIndex;
                }
            }
        }

        if (dynamicNameOnly != 0) {
            return dynamicNameOnly;
        }
        return staticNameOnly;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void clear() {
        head = 0;
        count = 0;
        currentSize = 0;
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void evictOldest() {
        int tailIndex = (head + count - 1) % entries.length;
        currentSize -= entries[tailIndex].byteSize();
        entries[tailIndex] = null;
        count--;
    }

    private void grow() {
        int newCapacity = entries.length * 2;
        Entry[] newEntries = new Entry[newCapacity];
        for (int idx = 0; idx < count; idx++) {
            newEntries[idx] = entries[(head + idx) % entries.length];
        }
        entries = newEntries;
        head = 0;
    }

    private int mapIndex(int index) {
        return (head + index) % entries.length;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "HPACK dynamic table index " + index + " out of range [0, " + count + ")");
        }
    }

}
