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
 * <h2>Sequencing &amp; Optimistic Concurrency (ADR-049)</h2>
 * <p>The durable log is the kernel's ordering / optimistic-concurrency boundary. Per
 * <b>ADR-049</b>, every {@code EventStreamAppender} binding MUST provide, for each
 * {@link StreamId}, a <b>per-stream total order</b>: concurrent appends to the same stream
 * are linearized and assigned strictly monotonic sequence numbers, and
 * {@link EventStreamReader#replayFromVersion(StreamId, long)} reads them back in that order.
 * This is the contract that log-derived views (event sourcing, KV-as-projection, the
 * replicated distributed log) rely on; it is owned <b>here</b>, on the Events SPI — not by
 * the transient {@link EventBus} (unordered by design) and not by Persistence. The separate
 * {@link eu.exeris.kernel.spi.flow.model.FlowSnapshot} {@code schemaVersion} CAS (ADR-013)
 * is flow-snapshot state concurrency, a distinct mechanism — not the event-log append OCC.
 *
 * <p><b>Optimistic-concurrency append (implementation slice).</b> The append surface will
 * gain an {@code expectedVersion} parameter and return the committed sequence — target shape
 * {@code AppendResult append(StreamId, long expectedVersion, EventDescriptor, EventPayload)}
 * with an {@code ANY_VERSION} sentinel that opts append-only callers out of the check. When
 * {@code expectedVersion} does not match the stream head, the append fails closed with
 * {@code EX-EVENT-6008} (version conflict) — no silent overwrite. This ADR-049 slice records
 * the decision and the contract; the signature change, the error code, and the
 * {@code AbstractEventStreamAppenderTck} binding on &ge;2 durable bindings land in the
 * implementation slice. The current single-argument {@link #append} is the pre-slice shape.
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
