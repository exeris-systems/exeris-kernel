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
 * <p>It builds a {@link JfrEventCatalogue} directly, as the other two do, and reads its buckets back
 * through it rather than through accessors of its own: a second shape of the same accessor is a
 * place for the two to drift apart. Reaching the warm-up through a seam on the Community catalogue
 * instead would rest on the Wall not granting this module Core, which it does — this module's POM
 * declares {@code exeris-kernel-core} and its sources import from it.
 *
 * @since 0.12
 */
final class KafkaJfrEventCatalogue {

    /**
     * The engine's own events: publish failure and consumer-loop failure.
     *
     * <p>Both fire from the publish or consume path, which are virtual threads, and a publish
     * failure arrives for every in-flight message at once — which is the worst moment to be loading
     * a class.
     */
    private static final List<String> ENGINE_HOT_PATH = List.of(
            "eu.exeris.kernel.community.kafka.KafkaPublishFailedEvent",
            "eu.exeris.kernel.community.kafka.KafkaConsumerLoopFailedEvent");

    /**
     * The appender's event, in its own group because the engine cannot warm it.
     *
     * <p>Separate from the engine's group because {@code KafkaEventEngine} does not construct, hold
     * or expose a {@code KafkaEventStreamAppender}: warming this event at engine start covers a path
     * that never emits it. The appender's constructor is public and is the only way to obtain one —
     * this module's own {@code CommunityKafkaEventStreamAppenderTckIT} and {@code …ReaderTckIT} build
     * one with no engine — and the path that emits is {@code append} → {@code send}, per append, on
     * the caller's virtual thread inside the per-stream lock.
     */
    private static final List<String> APPENDER_HOT_PATH = List.of(
            "eu.exeris.kernel.community.kafka.KafkaEventLogAppendFailedEvent");

    /** Empty, and the guard is what keeps that honest rather than a claim in this sentence. */
    private static final List<String> DELIBERATELY_COLD = List.of();

    /** Group keys, named once so a warm-up and the catalogue cannot disagree about them. */
    private static final String ENGINE_KEY = "kafka-engine";

    private static final String APPENDER_KEY = "kafka-appender";

    /**
     * Keyed by what warms each group rather than by a subsystem name: this driver has no
     * {@code Subsystem} of its own, and its two seams own different events.
     */
    private static final JfrEventCatalogue CATALOGUE = new JfrEventCatalogue(
            KafkaJfrEventCatalogue.class,
            Map.of(ENGINE_KEY, ENGINE_HOT_PATH, APPENDER_KEY, APPENDER_HOT_PATH),
            DELIBERATELY_COLD);

    private KafkaJfrEventCatalogue() {
        // Static catalogue — no instances.
    }

    /**
     * This module's catalogue: both buckets, and the behaviour they share with every other module's.
     *
     * @return the catalogue; never {@code null}
     */
    /* default */ static JfrEventCatalogue catalogue() {
        return CATALOGUE;
    }

    /**
     * Warms the engine's event classes on the calling thread, at {@code KafkaEventEngine.start()}.
     *
     * <p>No-arg, unlike the other two holders' {@code warmHotPath(String)}: they are keyed by
     * {@code Subsystem.name()} and dispatch on it, while this driver's groups are keyed by the seam
     * that warms them. A parameter here would be a key the caller could only ever get wrong.
     */
    /* default */ static void warmEngine() {
        CATALOGUE.warmHotPath(ENGINE_KEY);
    }

    /**
     * Warms the appender's event class on the calling thread, at appender construction.
     *
     * <p>A constructor is a sound seam here and is not one for a per-stream data structure such as
     * {@code PaqsScheduler}, which is built in a loop and would warm on every construction. An
     * appender is a binding built once per event log, so its construction is a start in everything
     * but name, and it is the one point every caller of {@code append} has passed through.
     */
    /* default */ static void warmAppender() {
        CATALOGUE.warmHotPath(APPENDER_KEY);
    }
}
