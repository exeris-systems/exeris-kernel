/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.fake;

import eu.exeris.kernel.core.fake.FakeHotPath;

/**
 * Stands in for a TCK contract class: it lives under {@code eu.exeris.kernel.tck.*} and calls the
 * fake production code, so every allocation in the round-trip test has a harness frame beneath a
 * production frame - the shape that the old classifier demoted to harness.
 */
public final class FakeZeroAllocTck {

    private FakeZeroAllocTck() {}

    /**
     * Runs the fake hot path.
     *
     * @param iterations how many times
     * @return total bytes requested
     */
    public static long runWorkload(int iterations) {
        long total = 0L;
        for (int i = 0; i < iterations; i++) {
            total += FakeHotPath.allocate();
        }
        return total;
    }
}
