/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when {@code KafkaPublishBus.publish} (or {@code publishAndAwait}) wraps a Kafka
 * driver exception as {@code EventBusException} — the publish-side post-mortem trail; the
 * symmetric consumer-side event is {@link KafkaConsumerLoopFailedEvent}.
 *
 * <p>Captures: engine name, topic (so operators can localise the failure to a specific event
 * type's topic-mapping), event ordinal (for type-level diagnostics without revealing payload
 * bytes), and the exception class + message. Payload bytes are NEVER logged — Kafka producer
 * credentials and event-domain data must not surface in JFR.
 */
@Name("eu.exeris.kernel.events.kafka.PublishFailed")
@Label("Kafka Publish Failed")
@Category({"Exeris Kernel", "Events", "Kafka"})
@Description("Emitted when KafkaPublishBus.publish wraps a Kafka driver exception "
        + "(timeout, broker disconnect, authorisation failure, etc.) as EventBusException. "
        + "Operators rely on this to attribute publish failures to specific topic + event "
        + "ordinal pairs — without it, the exception surface tells callers but leaves no "
        + "post-mortem trail for analysing publish-side failure rates.")
@StackTrace(false)
final class KafkaPublishFailedEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Topic")
    @Description("Kafka topic the publish targeted (per topicFor(eventType) mapping)")
    /* default */ String topic;

    @Label("Event Type Ordinal")
    @Description("Wire ordinal of the event type being published (descriptor.eventTypeOrdinal)")
    /* default */ int eventTypeOrdinal;

    @Label("Publish Mode")
    @Description("Either \"publish\" (fire-and-forget) or \"publishAndAwait\" (awaited future)")
    /* default */ String publishMode;

    @Label("Exception Class")
    @Description("Fully-qualified class name of the exception wrapped as EventBusException")
    /* default */ String exceptionClass;

    @Label("Exception Message")
    @Description("Exception message — secret-safe; Kafka credentials are never included")
    /* default */ String exceptionMessage;

    /* default */ static void emit(
            String engineName,
            String topic,
            int eventTypeOrdinal,
            String publishMode,
            String exceptionClass,
            String exceptionMessage) {
        KafkaPublishFailedEvent event = new KafkaPublishFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.topic = topic;
        event.eventTypeOrdinal = eventTypeOrdinal;
        event.publishMode = publishMode;
        event.exceptionClass = exceptionClass;
        event.exceptionMessage = exceptionMessage;
        event.commit();
    }
}
