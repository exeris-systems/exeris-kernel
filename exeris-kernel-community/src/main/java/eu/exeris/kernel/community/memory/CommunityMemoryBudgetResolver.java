/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.config.ConfigProvider;

/**
 * Resolves the Community allocator's memory-pressure budget from {@code memory.offHeapBudgetBytes}.
 *
 * <p>The budget is the denominator of the utilisation the allocator reports, and so of the
 * memory-pressure level derived from it. It is not an allocation cap: the allocator never refuses
 * a request for exceeding it. Absent, or {@code -1}, means no budget, which is the allocator's
 * unbudgeted behaviour. A budget of {@code 0} or below {@code -1} is refused, naming the key, rather
 * than read as "no budget": a misconfigured value must not silently disable the signal it was
 * written to enable.
 *
 * @since 0.13
 */
public final class CommunityMemoryBudgetResolver {

    /** Config key for the budget; see {@code docs/subsystems/config.md}. */
    public static final String KEY = "memory.offHeapBudgetBytes";

    /** The value that means "no budget". */
    private static final long NO_BUDGET = -1L;

    private CommunityMemoryBudgetResolver() {
    }

    /**
     * Returns the configured budget in bytes, or {@code -1} for none.
     *
     * @param configProvider the resolved provider, or {@code null} when none is bound
     * @return a positive budget in bytes, or {@code -1} when the key is absent or no provider is bound
     * @throws IllegalArgumentException if the key is present and is {@code 0} or below {@code -1}
     */
    public static long resolve(ConfigProvider configProvider) {
        if (configProvider == null) {
            return NO_BUDGET;
        }
        long value = configProvider.getLong(KEY).orElse(NO_BUDGET);
        if (value == 0L || value < NO_BUDGET) {
            throw new IllegalArgumentException(
                    KEY + " must be positive, or -1 for no budget, got: " + value);
        }
        return value;
    }
}
