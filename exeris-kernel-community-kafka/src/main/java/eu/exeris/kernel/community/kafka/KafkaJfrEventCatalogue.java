/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.core.telemetry.jfr.JfrEventCatalogue;

import java.util.List;
import java.util.Map;

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
 * <p>It builds a {@link JfrEventCatalogue} directly, as the other two do. An earlier version reached
 * the warm-up through a seam on the Community catalogue, on the stated ground that the Wall does not
 * grant this module Core — which is not so: this module's POM declares {@code exeris-kernel-core} and
 * its sources already import from it.
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

    /**
     * Keyed by the engine rather than by a subsystem name: this driver has no {@code Subsystem} of
     * its own, and {@code KafkaEventEngine.start()} is what warms it.
     */
    private static final JfrEventCatalogue CATALOGUE = new JfrEventCatalogue(
            KafkaJfrEventCatalogue.class, Map.of("kafka", HOT_PATH), DELIBERATELY_COLD);

    private KafkaJfrEventCatalogue() {
        // Static catalogue — no instances.
    }

    /** Warms this driver's event classes on the calling thread, at engine start. */
    /* default */ static void warmHotPath() {
        CATALOGUE.warmHotPath("kafka");
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
