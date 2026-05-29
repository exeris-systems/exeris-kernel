/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.Dynamic;

/**
 * Community-internal, tunable thresholds for persistence admission control.
 *
 * <p>Carries the parameters consumed by {@link CommunityPersistenceAdmissionController}
 * when deciding whether {@link CommunityPersistenceEngine#canServiceRequest()} admits or
 * sheds a request. Prior to ADR-035 these were hard-coded {@code static final} constants;
 * they are now operator-tunable (startup in Community, hot-reload in Enterprise) and the
 * small-pool reject behavior is recalibrated — see {@link #queueDepthAllowanceRatio}.
 *
 * <h2>Why this exists (ADR-035)</h2>
 * <p>Under a CPU-constrained profile ({@code -XX:ActiveProcessorCount=1}) the adaptive pool
 * size collapses to its floor (≈2 connections). The pre-035 gate rejected as soon as the
 * pool was full and a single acquire was queued, shedding the overwhelming majority of an
 * otherwise trivial read workload (queries draining sub-millisecond). The recalibrated
 * default lets a small pool absorb a transient queue proportional to its drain rate instead
 * of fast-failing on the first waiter, while preserving genuine backpressure once the queue
 * grows beyond {@link #queueDepthAllowance(int)}.
 *
 * <h2>Hot-reload (the Wall)</h2>
 * <p>{@link #CURRENT} is a {@code static volatile} immutable record annotated {@link Dynamic}.
 * Community resolves it once at bootstrap from {@link ConfigProvider} and never reloads
 * ({@link ConfigProvider#watch} is a no-op in Community); Enterprise swaps it atomically on
 * file change. The admission controller reads {@link #CURRENT} at the decision call site,
 * so a swap takes effect on the next admission decision with no locks and no heap churn.
 *
 * @param hardSaturationThreshold      shed when {@code active/max} reaches this ratio AND the
 *                                     queue exceeds the allowance; range (0, 1].
 * @param guardBandThreshold           early-fairness band entry ratio; range (0, 1].
 * @param fairnessStressThreshold      fairness ratio above which sustained stress is declared.
 * @param fairnessQueueDepthThreshold  queue depth above which fairness stress is considered.
 * @param earlyGuardBandHeadroomRatio  headroom fraction of {@code max} for early guard-band reject.
 * @param earlyGuardBandHeadroomCap    absolute cap on the early guard-band headroom window.
 * @param queueDepthAllowanceRatio     pending-acquires tolerated per unit of pool size before a
 *                                     full/saturated pool sheds. Allowance scales with pool size
 *                                     because a larger pool drains a queue proportionally faster
 *                                     for the same expected wait. Default {@code 8.0} keeps the
 *                                     expected wait within the No-Waste-Compute bound for
 *                                     sub-millisecond queries while restoring availability on
 *                                     tiny pools. Set to {@code 0.0} to restore the strict
 *                                     pre-035 "shed on first waiter" contract.
 * @since 0.8.0
 * @see CommunityPersistenceAdmissionController
 * @see Dynamic
 */
public record CommunityAdmissionConfig(
        double hardSaturationThreshold,
        double guardBandThreshold,
        double fairnessStressThreshold,
        long fairnessQueueDepthThreshold,
        double earlyGuardBandHeadroomRatio,
        int earlyGuardBandHeadroomCap,
        double queueDepthAllowanceRatio) {

    /** Config file (relative to the config dir) carrying admission tunables. */
    public static final String CONFIG_FILE = "persistence.json";
    /** Dot-path key prefix for the flat admission tunables. */
    public static final String KEY_PREFIX = "persistence.admission";

    /**
     * Recalibrated defaults (ADR-035). Threshold values match the pre-035 constants; the new
     * {@code queueDepthAllowanceRatio=8.0} is what restores small-pool availability.
     */
    public static final CommunityAdmissionConfig DEFAULT = new CommunityAdmissionConfig(
            0.90d, 0.85d, 0.90d, 1L, 0.15d, 3, 8.0d);

    /**
     * Live, hot-reloadable admission configuration. Read at the admission decision call site.
     *
     * <p>Community sets this once at bootstrap and never reloads; Enterprise swaps it
     * atomically via the {@link Dynamic} watcher. Initialized to {@link #DEFAULT} so the
     * gate is well-defined before any subsystem wiring runs (e.g. in unit tests that
     * construct an engine directly).
     */
    // CURRENT is upper-cased per the @Dynamic convention (it is a hot-reload pointer, not a
    // mutable bean property); the standard field-naming rule does not apply to @Dynamic fields.
    @Dynamic(file = CONFIG_FILE, key = KEY_PREFIX, required = false)
    @SuppressWarnings({"PMD.MutableStaticState", "PMD.FieldNamingConventions"})
    public static volatile CommunityAdmissionConfig CURRENT = DEFAULT;

    private static final long MIN_FAIRNESS_QUEUE_DEPTH = 0L;
    private static final int MIN_EARLY_GUARD_BAND_HEADROOM_CAP = 1;

    private static final String KEY_HARD_SATURATION = KEY_PREFIX + ".hardSaturationThreshold";
    private static final String KEY_GUARD_BAND = KEY_PREFIX + ".guardBandThreshold";
    private static final String KEY_FAIRNESS_STRESS = KEY_PREFIX + ".fairnessStressThreshold";
    private static final String KEY_FAIRNESS_QUEUE_DEPTH = KEY_PREFIX + ".fairnessQueueDepthThreshold";
    private static final String KEY_EARLY_HEADROOM_RATIO = KEY_PREFIX + ".earlyGuardBandHeadroomRatio";
    private static final String KEY_EARLY_HEADROOM_CAP = KEY_PREFIX + ".earlyGuardBandHeadroomCap";
    private static final String KEY_QUEUE_ALLOWANCE_RATIO = KEY_PREFIX + ".queueDepthAllowanceRatio";

    /**
     * Strict validation — fail fast on out-of-range tunables rather than silently degrading
     * the admission gate.
     */
    public CommunityAdmissionConfig {
        requireRatio(hardSaturationThreshold, "hardSaturationThreshold");
        requireRatio(guardBandThreshold, "guardBandThreshold");
        requireRatio(fairnessStressThreshold, "fairnessStressThreshold");
        if (fairnessQueueDepthThreshold < MIN_FAIRNESS_QUEUE_DEPTH) {
            throw new IllegalArgumentException("fairnessQueueDepthThreshold must be >= 0");
        }
        if (earlyGuardBandHeadroomRatio < 0.0d || earlyGuardBandHeadroomRatio > 1.0d) {
            throw new IllegalArgumentException("earlyGuardBandHeadroomRatio must be in [0, 1]");
        }
        if (earlyGuardBandHeadroomCap < MIN_EARLY_GUARD_BAND_HEADROOM_CAP) {
            throw new IllegalArgumentException("earlyGuardBandHeadroomCap must be >= 1");
        }
        if (queueDepthAllowanceRatio < 0.0d || !Double.isFinite(queueDepthAllowanceRatio)) {
            throw new IllegalArgumentException("queueDepthAllowanceRatio must be finite and >= 0");
        }
    }

    private static void requireRatio(double value, String name) {
        if (value <= 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be in (0, 1]");
        }
    }

    /**
     * Maximum pending acquires tolerated before a full/saturated pool sheds, scaled to pool size.
     *
     * <p>{@code ceil(max * queueDepthAllowanceRatio)}. A larger pool drains a queue
     * proportionally faster, so a proportional allowance keeps the expected wait roughly
     * constant. Always {@code >= 0}; equals {@code 0} only when the ratio is {@code 0}
     * (strict pre-035 behavior).
     *
     * @param maxConnections configured maximum pool size
     * @return queue-depth allowance for that pool size
     */
    public int queueDepthAllowance(int maxConnections) {
        if (maxConnections <= 0) {
            return 0;
        }
        return (int) Math.ceil(maxConnections * queueDepthAllowanceRatio);
    }

    /**
     * Resolves an admission configuration from a {@link ConfigProvider}, falling back to
     * {@link #DEFAULT} per field for any unset key. Used at bootstrap and on hot-reload.
     *
     * @param configProvider bound configuration provider
     * @return resolved configuration (never {@code null})
     */
    public static CommunityAdmissionConfig fromConfigProvider(ConfigProvider configProvider) {
        return new CommunityAdmissionConfig(
                getDouble(configProvider, KEY_HARD_SATURATION, DEFAULT.hardSaturationThreshold),
                getDouble(configProvider, KEY_GUARD_BAND, DEFAULT.guardBandThreshold),
                getDouble(configProvider, KEY_FAIRNESS_STRESS, DEFAULT.fairnessStressThreshold),
                configProvider.getLong(KEY_FAIRNESS_QUEUE_DEPTH).orElse(DEFAULT.fairnessQueueDepthThreshold),
                getDouble(configProvider, KEY_EARLY_HEADROOM_RATIO, DEFAULT.earlyGuardBandHeadroomRatio),
                configProvider.getInt(KEY_EARLY_HEADROOM_CAP).orElse(DEFAULT.earlyGuardBandHeadroomCap),
                getDouble(configProvider, KEY_QUEUE_ALLOWANCE_RATIO, DEFAULT.queueDepthAllowanceRatio));
    }

    // ConfigProvider has no getDouble — parse from the string view, ignoring malformed values.
    private static double getDouble(ConfigProvider configProvider, String key, double fallback) {
        return configProvider.getString(key)
                .map(String::trim)
                .flatMap(CommunityAdmissionConfig::parseDouble)
                .orElse(fallback);
    }

    private static java.util.Optional<Double> parseDouble(String raw) {
        try {
            return java.util.Optional.of(Double.valueOf(raw));
        } catch (NumberFormatException _) {
            return java.util.Optional.empty();
        }
    }
}
