/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import java.util.concurrent.TimeUnit;

/**
 * Public test helper for cross-package {@link ResourceArbiter} testing.
 *
 * <p>Exposes the {@link ResourceArbiter} testing constructor
 * (which takes a custom {@code startNs}) to test code in other packages within the
 * {@code exeris-kernel-core} test-jar.
 *
 * <h2>Why this exists</h2>
 * <p>The {@link ResourceArbiter} testing constructor is package-private to prevent
 * accidental use in production code. Test classes outside the
 * {@code eu.exeris.kernel.core.memory} package need a grace-period-expired arbiter
 * without a 30-second sleep. This factory provides a safe bridge.
 *
 * @since 0.5.0
 */
public final class ResourceArbiterTestHelper {

    private ResourceArbiterTestHelper() {
    }

    /**
     * Creates a {@link ResourceArbiter} whose 30-second grace period has definitively expired.
     *
     * @param watermarkManager the watermark manager to use
     * @return an arbiter ready for immediate load-shedding decisions
     */
    public static ResourceArbiter expiredGraceArbiter(WatermarkManager watermarkManager) {
        long expiredNs = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
        return new ResourceArbiter(watermarkManager, expiredNs);
    }
}

