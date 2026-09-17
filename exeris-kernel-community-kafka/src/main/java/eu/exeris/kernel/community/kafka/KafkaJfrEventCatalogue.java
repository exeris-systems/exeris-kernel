/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;

import java.util.List;

/**
 * Every JFR event class this driver declares, split the way the Core and Community catalogues split
 * theirs: warmed when the engine starts, or deliberately left to initialise at its first emit.
 *
 * <p>This module needs its own catalogue rather than an entry in the Community one for two reasons.
 * Its event classes ship in its artifact, so they resolve through its loader and not Community's;
 * and the Community coverage guard cannot see this module at all — it is the module that depends on
 * Community, not the other way round. {@code KafkaJfrEventCatalogueTest} is the guard that keeps
 * this list complete, and it lives here for the same reason.
 *
 * @since 0.12
 */
final class KafkaJfrEventCatalogue {

    /**
     * All three of this driver's events, and all three warmed.
     *
     * <p>Each fires from the publish or consume path, which are virtual threads, and a publish
     * failure arrives for every in-flight message at once — which is the worst moment to be loading
     * a class.
     */
    private static final List<String> HOT_PATH = List.of(
            "eu.exeris.kernel.community.kafka.KafkaPublishFailedEvent",
            "eu.exeris.kernel.community.kafka.KafkaEventLogAppendFailedEvent",
            "eu.exeris.kernel.community.kafka.KafkaConsumerLoopFailedEvent");

    /** Empty, and the guard is what keeps that honest rather than a claim in this sentence. */
    private static final List<String> DELIBERATELY_COLD = List.of();

    private KafkaJfrEventCatalogue() {
        // Static catalogue — no instances.
    }

    /** Warms this driver's event classes on the calling thread, at engine start. */
    /* default */ static void warmHotPath() {
        CommunityJfrEventCatalogue.warmDriverEvents(KafkaJfrEventCatalogue.class, HOT_PATH);
    }

    /**
     * The warmed event classes.
     *
     * @return their fully-qualified names; never {@code null}
     */
    /* default */ static List<String> hotPath() {
        return HOT_PATH;
    }

    /**
     * The event classes deliberately left cold.
     *
     * @return their fully-qualified names; never {@code null}
     */
    /* default */ static List<String> deliberatelyCold() {
        return DELIBERATELY_COLD;
    }
}
