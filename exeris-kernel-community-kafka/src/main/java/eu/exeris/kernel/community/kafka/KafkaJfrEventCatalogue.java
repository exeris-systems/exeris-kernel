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
 * through it rather than through accessors of its own — a third shape of the same class was one more
 * place for the two to drift apart. An earlier version reached the warm-up through a seam on the
 * Community catalogue, on the stated ground that the Wall does not grant this module Core — which is
 * not so: this module's POM declares {@code exeris-kernel-core} and its sources already import from
 * it.
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
     * <p>It was in the engine's group, which was wrong in a way no guard could see:
     * {@code KafkaEventEngine} does not construct, hold or expose a
     * {@code KafkaEventStreamAppender}. The appender's constructor is public, it is the only way to
     * obtain one, and this module's own {@code CommunityKafkaEventStreamAppenderTckIT} and
     * {@code …ReaderTckIT} build one without instantiating an engine at all. So the event was warmed
     * by something unrelated to the class that emits it, and the path that does emit it —
     * {@code append} → {@code send}, per append, on the caller's virtual thread inside the
     * per-stream lock — warmed nothing.
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
     * <p>A constructor, and that is not the seam the review of an earlier round rejected: that one
     * was {@code PaqsScheduler}'s, a per-stream data structure built in a loop. An appender is a
     * binding built once per event log, so its construction is a start in everything but name — and
     * it is the only point every caller of {@code append} has passed through.
     */
    /* default */ static void warmAppender() {
        CATALOGUE.warmHotPath(APPENDER_KEY);
    }
}
