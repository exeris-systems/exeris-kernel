/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import java.time.Instant;

/**
 * SPI: replay query API over the durable event log.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>Implementation-blind. Bindings (PostgreSQL outbox, Kafka consumer, Enterprise
 * off-heap log) provide the cursor; callers see only {@link EventStream} hand-off.
 *
 * <h2>Discovery &amp; Wiring</h2>
 * <p>An implementation is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_STREAM_READER}
 * by the bootstrapper before {@link EventEngine#start()} (analogous to
 * {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore}).
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>CQRS projection rebuild — re-read all events for a projection's source streams.</li>
 *   <li>Saga forensic replay — reconstruct a saga's effective inbound event log after
 *       a cross-restart wake on a different node.</li>
 *   <li>Audit / compliance — replay every event of a given type within a timestamp window.</li>
 * </ul>
 *
 * <h2>Cursor Lifecycle</h2>
 * <p>Each method returns a fresh {@link EventStream} that owns an underlying cursor. The reader
 * and the streams it hands out are released independently.
 *
 * <h2>Ordering (ADR-049)</h2>
 * <p>Replay honours the per-stream total order the {@link EventStreamAppender} established:
 * {@link #replayFromVersion(StreamId, long)} yields the stream's events in strictly ascending
 * {@code committedSequence} order (the 1-based sequence assigned at append). This is the read
 * side of the ordering boundary the durable log owns; the transient {@link EventBus} makes no
 * ordering promise.
 *
 * <p><b>Ownership:</b> the caller closes every {@link EventStream} it is handed, and closes the
 * reader itself when done with it; closing the reader does not close the streams it has already
 * returned
 *
 * @implSpec {@link #close()} releases the reader's shared driver resources (connection-pool slot,
 *           consumer-group session) and is idempotent. Every query returns a live stream, never
 *           {@code null} — an empty result is an empty stream.
 * @apiNote The slot may be unbound: a broker with no replay capability binds nothing here. Treat
 *          the absence as "this broker does not support replay" rather than as a hard error.
 * @since 0.7
 * @see EventStream
 * @see EventStreamAppender
 */
public interface EventStreamReader extends AutoCloseable {

    /**
     * Replays every event for the given stream whose {@code occurredAt} is at or after
     * {@code fromTimestamp}.
     *
     * @param streamId      the stream to replay; must not be {@code null}
     * @param fromTimestamp inclusive lower bound on {@code occurredAt}; must not be {@code null}
     * @return a live {@link EventStream} over the matching events, never {@code null}; the
     *         caller closes it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         {@code EX-EVENT-6001} on driver-level failure (connection lost, cursor open
     *         rejected), unless the binding raises a more specific {@code EX-EVENT-*} code
     */
    EventStream replayFrom(StreamId streamId, Instant fromTimestamp);

    /**
     * Replays every event for the given stream whose monotonic version is at or after
     * {@code fromVersion}.
     *
     * <p>Bindings that do not maintain a per-stream monotonic version (e.g. raw Kafka
     * topic without an aggregate-version offset) MAY map this query onto their native
     * offset semantics; bindings that do (PostgreSQL outbox, Enterprise off-heap log)
     * use the version directly.
     *
     * @param streamId    the stream to replay; must not be {@code null}
     * @param fromVersion inclusive lower bound on the per-stream version; {@code >= 0}
     * @return a live {@link EventStream} over the matching events, never {@code null}; the
     *         caller closes it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         {@code EX-EVENT-6001} on driver-level failure, unless the binding raises a more
     *         specific {@code EX-EVENT-*} code
     */
    EventStream replayFromVersion(StreamId streamId, long fromVersion);

    /**
     * Cross-stream replay of every event of the given type since {@code fromTimestamp}.
     *
     * <p>Used for projection rebuilds where the projection observes events from many
     * stream instances of the same {@code eventType}.
     *
     * @param eventType     non-blank event type name as registered in {@link EventRegistry}
     * @param fromTimestamp inclusive lower bound on {@code occurredAt}; must not be {@code null}
     * @return a live {@link EventStream} over the matching events, never {@code null}; the
     *         caller closes it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         {@code EX-EVENT-6001} on driver-level failure, unless the binding raises a more
     *         specific {@code EX-EVENT-*} code
     */
    EventStream replayByType(String eventType, Instant fromTimestamp);

    /**
     * Gives back the driver resources the reader holds across all of its queries — the
     * connection-pool slot or consumer-group session, not any individual cursor.
     *
     * @implSpec Idempotent. Streams returned earlier remain valid until they themselves are
     *           closed; closing the reader neither cancels nor drains them.
     */
    @Override
    void close();
}
