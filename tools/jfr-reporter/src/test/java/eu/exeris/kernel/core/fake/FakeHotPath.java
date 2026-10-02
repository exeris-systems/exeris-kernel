/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.fake;

/**
 * Stands in for production code in the reporter's round-trip test: its package is
 * {@code eu.exeris.kernel.core.*}, its name carries no harness marker, and it allocates something
 * JFR is guaranteed to see - an array larger than any TLAB, which fires
 * {@code jdk.ObjectAllocationOutsideTLAB} and a sample on every call.
 */
public final class FakeHotPath {

    /** Two mebibytes: larger than a TLAB, so the allocation goes outside it. */
    public static final int ARRAY_BYTES = 2 * 1024 * 1024;

    @SuppressWarnings("unused")
    private static volatile byte[] sink;

    private FakeHotPath() {}

    /**
     * Allocates one large array and publishes it so the allocation cannot be elided.
     *
     * @return the array length
     */
    public static int allocate() {
        byte[] block = new byte[ARRAY_BYTES];
        block[0] = 1;
        sink = block;
        return block.length;
    }
}
