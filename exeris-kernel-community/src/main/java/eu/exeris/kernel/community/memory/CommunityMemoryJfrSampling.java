/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;

/**
 * Resolves the allocation-event sampling stride.
 *
 * <p>Read through {@link ConfigProvider} first and only then from the system property. Until
 * v0.12 the property was the sole source, which made the stride reachable by {@code -D} and by
 * nothing else — invisible to a config file, to the environment, and to
 * {@code docs/subsystems/config.md}. The provider already consults
 * {@code -Dexeris.<key>} as one of its own sources, so the old invocation keeps working; what
 * changes is that it is no longer the only one.
 */
final class CommunityMemoryJfrSampling {

    /** Config key; the provider resolves {@code -Dexeris.memory.jfr.sampleEvery} for it too. */
    /* default */ static final String SAMPLE_EVERY_KEY = "memory.jfr.sampleEvery";

    private static final String SAMPLE_EVERY_PROPERTY = "exeris.community.memory.jfr.sampleEvery";
    private static final int DEFAULT_SAMPLE_EVERY = 64;
    private static final int MIN_SAMPLE_EVERY = 1;

    private final int sampleEvery;

    private CommunityMemoryJfrSampling(int sampleEvery) {
        this.sampleEvery = sampleEvery;
    }

    /* default */ static CommunityMemoryJfrSampling fromSystemProperties() {
        return new CommunityMemoryJfrSampling(resolveSampleEvery());
    }

    /* default */ boolean shouldEmit(long totalCount) {
        return sampleEvery <= MIN_SAMPLE_EVERY || (totalCount > 0 && totalCount % sampleEvery == 0);
    }

    /* default */ int sampleEvery() {
        return sampleEvery;
    }

    /**
     * The allocator is constructed inside the boot scope, so {@code CURRENT_CONFIG} is bound here.
     * Defensive read regardless: a driver constructed outside a boot (tests, tooling) must still
     * resolve, and falling through to the property is exactly the pre-0.12 behaviour.
     *
     * @return the configured stride, or {@code null} when no provider or no key
     */
    private static Integer fromConfigProvider() {
        if (!KernelProviders.CURRENT_CONFIG.isBound()) {
            return null;
        }
        ConfigProvider config = KernelProviders.CURRENT_CONFIG.get();
        return config == null ? null : config.getInt(SAMPLE_EVERY_KEY).orElse(null);
    }

    private static int resolveSampleEvery() {
        Integer configured = fromConfigProvider();
        if (configured != null) {
            return configured > 0 ? configured : MIN_SAMPLE_EVERY;
        }
        String raw = System.getProperty(SAMPLE_EVERY_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SAMPLE_EVERY;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : MIN_SAMPLE_EVERY;
        } catch (NumberFormatException _) {
            return DEFAULT_SAMPLE_EVERY;
        }
    }
}