/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when an outbox entry is moved to the dead-letter queue after exhausting
 * all delivery retries or receiving an unrecoverable broker rejection.
 *
 * <p>Emitted by the outbox batch flusher's per-event retry path, for one entry out of a
 * partially-failed batch: either the retry loop ran out of its configured attempt budget without
 * an acknowledged publish, or a retry attempt raised a {@code RuntimeException} that the flusher
 * treats as unrecoverable. Either way, this event commits immediately before the entry is moved
 * to the dead-letter store.
 */
@Label("Outbox DLQ Transition")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxDlqEvent extends Event {

    /**
     * The entry's registered event type, as the decimal-string form of its
     * {@code EventRegistry} ordinal — not the type's name.
     */
    @Label("Event Type")
    public String eventType;

    /** High 64 bits of the entry's aggregate stream UUID. */
    @Label("Stream ID High")
    public long streamIdHigh;

    /** Low 64 bits of the entry's aggregate stream UUID. */
    @Label("Stream ID Low")
    public long streamIdLow;

    /**
     * Why the entry could not be delivered: the literal {@code "max retries exhausted"} when the
     * retry budget ran out, otherwise the message of the {@code RuntimeException} that aborted the
     * retry (or the literal {@code "exception"} when that message was null or blank).
     */
    @Label("Failure Reason")
    public String reason;

    /**
     * The outbox's configured maximum per-event retry count. This is the configured limit, not a
     * count of attempts actually made before this event was emitted — the two coincide only when
     * the retry loop ran to exhaustion.
     */
    @Label("Retry Count")
    public int retryCount;
/**
 * Creates an unrecorded event.
 *
 * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
 * committed contributes nothing to a recording.
 */
public OutboxDlqEvent() {
    // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
    super();
}

}
