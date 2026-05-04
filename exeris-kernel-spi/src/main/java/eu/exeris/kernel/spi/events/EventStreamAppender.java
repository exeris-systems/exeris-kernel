/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.events;

/**
 * SPI: durable append API for distributed event-log bindings.
 *
 * <h2>Role vs {@link EventBus}</h2>
 * <p>{@link EventBus#publish(EventDescriptor, EventPayload)} is the in-process pub/sub
 * dispatch path; it does not guarantee durability beyond the local engine. {@code
 * EventStreamAppender} is the explicit durable-append path that bindings (Kafka producer,
 * PostgreSQL outbox writer, Enterprise off-heap log) implement to commit an event to the
 * shared distributed log. Most callers route through the transactional outbox and the
 * orchestrator picks the broker port; direct use of this SPI is reserved for sites that
 * need topic/partition control (e.g. choreography emit on a non-default partition).
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>Implementation-blind. No Kafka {@code ProducerRecord}, no JDBC {@code PreparedStatement},
 * no Panama {@code MemorySegment} layout escapes the contract.
 *
 * <h2>Discovery &amp; Wiring</h2>
 * <p>An implementation is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_STREAM_APPENDER}
 * by the bootstrapper before {@link EventEngine#start()}. When unbound, callers MUST
 * fall back to the {@link EventBus} dispatch path; absence is not a hard error.
 *
 * <h2>RAII Ownership</h2>
 * <p>Same protocol as {@link EventBus#publish}: the appender takes ownership of
 * {@code payload} on entry. After the call returns (success or thrown exception),
 * the caller MUST NOT call {@link EventPayload#close()} on the same reference.
 *
 * @since 0.7.0
 * @see EventStreamReader
 */
@FunctionalInterface
public interface EventStreamAppender {

    /**
     * Appends a single event to the durable log identified by {@code streamId}.
     *
     * <p>Synchronous on the contract surface — the call returns only after the binding
     * has accepted ownership of the payload (Kafka producer enqueue, JDBC outbox INSERT
     * commit, etc.). Implementations MAY perform the actual broker round-trip
     * asynchronously off the caller thread.
     *
     * @param streamId   target stream; {@link StreamId#streamIdHigh()} /
     *                   {@link StreamId#streamIdLow()} MUST match the stream UUID carried
     *                   by {@code descriptor}
     * @param descriptor routing metadata (non-null)
     * @param payload    event payload; ownership transfers to the appender (non-null;
     *                   use {@link EventPayload#empty()} for no-data events)
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         when the binding cannot accept the append (broker disconnected, outbox
     *         INSERT rejected, etc.)
     */
    void append(StreamId streamId, EventDescriptor descriptor, EventPayload payload);
}
