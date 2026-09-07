/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;

/**
 * Resolves the allocation/release-event sampling stride.
 *
 * <p>Read through {@link ConfigProvider} first, falling back to the
 * {@code exeris.community.memory.jfr.sampleEvery} system property whenever no
 * {@link KernelProviders#CURRENT_CONFIG} is bound or the bound provider has no value for
 * {@link #SAMPLE_EVERY_KEY}. The provider itself also consults {@code -Dexeris.<key>} as
 * one of its own sources, so a {@code -D}-only invocation resolves through either path.
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
     * resolve, which it does because a {@code null} return here sends the caller,
     * {@link #resolveSampleEvery()}, on to its own system-property read.
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