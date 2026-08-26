/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

final class CommunityMemoryJfrSampling {

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

    private static int resolveSampleEvery() {
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