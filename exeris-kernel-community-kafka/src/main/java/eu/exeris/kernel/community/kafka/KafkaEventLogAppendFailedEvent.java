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
 * Emitted when {@code KafkaEventStreamAppender.append} fails to write an event to the durable Kafka
 * event log (producer send timed out, broker unreachable, interrupted) before it wraps the cause as
 * an {@code EventEngineException} (ADR-049). The Kafka sibling of {@code EventLogAppendFailedEvent}
 * (which carries a JDBC SQLSTATE that has no Kafka analog), so it records a Kafka-appropriate reason
 * token plus the failing exception class instead.
 *
 * <p><b>Secret-safe.</b> Carries the stream type qualifier, a coarse reason token, and the failing
 * exception's class name — never the payload bytes, the failure message, or {@code bootstrap.servers}
 * / credentials. {@code @StackTrace(false)}, guarded by {@link Event#isEnabled()}.
 *
 * @since 0.10
 */
@Name("eu.exeris.kernel.events.kafka.EventLogAppendFailed")
@Label("Kafka Event-Log Append Failed")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted when KafkaEventStreamAppender.append fails to write to the durable Kafka event "
        + "log. Carries stream type, a coarse reason token, and the failing exception class — never "
        + "payload bytes, the message, or broker credentials (secret-safe).")
@StackTrace(false)
public final class KafkaEventLogAppendFailedEvent extends Event {

    /** Reason token: producer send failed (broker error / timeout). */
    public static final String REASON_KAFKA = "KAFKA";

    /** Reason token: the appending thread was interrupted mid-send. */
    public static final String REASON_INTERRUPTED = "INTERRUPTED";

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Stream Type")
    @Description("StreamId.streamType() of the target stream — a type qualifier, never payload content")
    /* default */ String streamType;

    @Label("Reason")
    @Description("Coarse failure token: KAFKA (send failed) or INTERRUPTED")
    /* default */ String reason;

    @Label("Failure Class")
    @Description("Class name of the failing exception; no message is recorded (secret-safe)")
    /* default */ String failureClass;

    public static void emit(String engineName, String streamType, String reason, Throwable failure) {
        KafkaEventLogAppendFailedEvent event = new KafkaEventLogAppendFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.streamType = streamType;
        event.reason = reason;
        event.failureClass = failure == null ? "" : failure.getClass().getName();
        event.commit();
    }
}
