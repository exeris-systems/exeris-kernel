/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;

/**
 * Resolves Community transport reactor count from explicit config or deterministic CPU auto-tuning.
 */
public final class CommunityReactorCountResolver {

    private static final int LOW_CPU_THRESHOLD = 2;
    private static final int MID_CPU_THRESHOLD = 8;
    private static final int LOW_CPU_RESERVE = 0;
    private static final int MID_CPU_RESERVE = 1;
    private static final int HIGH_CPU_RESERVE = 2;

    private CommunityReactorCountResolver() {
    }

    public static int resolve(ConfigProvider configProvider) {
        return resolve(configProvider, Runtime.getRuntime().availableProcessors());
    }

    /* default */
    static int resolve(ConfigProvider configProvider, int availableProcessors) {
        int cpu = Math.max(1, availableProcessors);
        if (configProvider == null) {
            return Math.max(1, cpu - defaultReserveCores(cpu));
        }

        int transportReactorCount = configProvider.getInt("transport.reactorCount").orElse(0);
        if (transportReactorCount > 0) {
            return transportReactorCount;
        }

        int networkReactorCount = configProvider.getInt("network.reactorCount").orElse(0);
        if (networkReactorCount > 0) {
            return networkReactorCount;
        }

        int reserveCores = Math.max(0,
            configProvider.getInt("transport.auto.reserveCores").orElse(defaultReserveCores(cpu)));
        int minReactors = Math.max(1, configProvider.getInt("transport.auto.minReactors").orElse(1));
        int maxReactors = configProvider.getInt("transport.auto.maxReactors").orElse(0);

        int candidate = Math.max(1, cpu - reserveCores);
        int maxBound = maxReactors <= 0 ? cpu : Math.max(1, maxReactors);
        if (maxBound < minReactors) {
            maxBound = minReactors;
        }

        return Math.clamp(candidate, minReactors, maxBound);
    }

    private static int defaultReserveCores(int cpu) {
        if (cpu <= LOW_CPU_THRESHOLD) {
            return LOW_CPU_RESERVE;
        }
        if (cpu <= MID_CPU_THRESHOLD) {
            return MID_CPU_RESERVE;
        }
        return HIGH_CPU_RESERVE;
    }
}
