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

@Name("eu.exeris.kernel.events.kafka.ConsumerLoopFailed")
@Label("Kafka Consumer Loop Failed")
@Category({"Exeris Kernel", "Events", "Kafka"})
@Description("Emitted when the Kafka consumer poll loop exits via an unhandled exception "
        + "(broker disconnect, deserialisation error, KafkaException). Operators rely on this "
        + "to detect silent consumer-thread death — without it, isRunning() simply flips false "
        + "and events stop arriving with no further signal.")
@StackTrace(false)
final class KafkaConsumerLoopFailedEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Consumer Group Id")
    @Description("Kafka consumer group id of the failed loop")
    /* default */ String consumerGroupId;

    @Label("Exception Class")
    @Description("Fully-qualified class name of the exception that exited the loop")
    /* default */ String exceptionClass;

    @Label("Exception Message")
    @Description("Exception message — secret-safe; Kafka credentials are never included")
    /* default */ String exceptionMessage;

    /* default */ static void emit(
            String engineName,
            String consumerGroupId,
            String exceptionClass,
            String exceptionMessage) {
        KafkaConsumerLoopFailedEvent event = new KafkaConsumerLoopFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.consumerGroupId = consumerGroupId;
        event.exceptionClass = exceptionClass;
        event.exceptionMessage = exceptionMessage;
        event.commit();
    }
}
