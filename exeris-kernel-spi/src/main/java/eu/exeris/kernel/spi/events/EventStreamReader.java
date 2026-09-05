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
 * {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore}). When unbound, replay
 * APIs are not available — application code SHOULD treat the absence as
 * "broker does not support replay" rather than as a hard error.
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
 * <p>Each method returns a fresh {@link EventStream} that owns an underlying cursor.
 * Callers MUST close every returned stream. {@link #close()} on the reader itself
 * releases shared driver resources (connection pool slot, consumer group session) and
 * is idempotent.
 *
 * <h2>Ordering (ADR-049)</h2>
 * <p>Replay honours the per-stream total order the {@link EventStreamAppender} established:
 * {@link #replayFromVersion(StreamId, long)} yields the stream's events in strictly ascending
 * {@code committedSequence} order (the 1-based sequence assigned at append). This is the read
 * side of the ordering boundary the durable log owns; the transient {@link EventBus} makes no
 * ordering promise.
 *
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
     * @return live {@link EventStream}; the caller MUST close it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         on driver-level failure (connection lost, cursor open rejected, etc.)
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
     * @return live {@link EventStream}; the caller MUST close it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         on driver-level failure
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
     * @return live {@link EventStream}; the caller MUST close it
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         on driver-level failure
     */
    EventStream replayByType(String eventType, Instant fromTimestamp);

    /**
     * Releases shared driver resources held by the reader.
     * Idempotent. Open {@link EventStream} instances returned earlier remain valid
     * until they themselves are closed; {@code close()} on the reader does not
     * cancel them.
     */
    @Override
    void close();
}
