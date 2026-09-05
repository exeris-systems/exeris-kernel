/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * are linearized and assigned strictly monotonic, 1-based sequence numbers, and
 * {@link EventStreamReader#replayFromVersion(StreamId, long)} reads them back in that order.
 * This is the contract log-derived views (event sourcing, KV-as-projection, the replicated
 * distributed log) rely on; it is owned <b>here</b>, on the Events SPI — not by the transient
 * {@link EventBus} (unordered by design) and not by Persistence. The separate
 * {@link eu.exeris.kernel.spi.flow.model.FlowSnapshot} {@code schemaVersion} CAS (ADR-013) is
 * flow-snapshot state concurrency, a distinct mechanism — not the event-log append OCC.
 *
 * <p><b>Version model.</b> A stream's head sequence starts at {@code 0} (empty). Each append
 * assigns the next sequence and returns it as {@link AppendResult#committedSequence()} (1-based:
 * the first event of a stream commits at {@code 1}).
 * <ul>
 *   <li>{@link #ANY_VERSION} — skip the concurrency check; append unconditionally at
 *       {@code head + 1}. For append-only / non-CAS producers.</li>
 *   <li>{@code expectedVersion == N} ({@code N >= 0}) — require the current head to equal
 *       {@code N}; on match the event commits at {@code N + 1}, on mismatch the append fails
 *       closed with {@link eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException}
 *       ({@code EX-EVENT-6008}) and the head is unchanged. Use {@code expectedVersion == 0} for
 *       the first event of a new stream.</li>
 * </ul>
 *
 * @since 0.7
 * @see EventStreamReader
 * @see AppendResult
 */
@FunctionalInterface
public interface EventStreamAppender {

    /**
     * Sentinel {@code expectedVersion} for {@link #append} that skips the optimistic-concurrency
     * check — the event is appended unconditionally at the stream head. For append-only producers
     * that do not enforce per-stream compare-and-set.
     */
    long ANY_VERSION = -1L;

    /**
     * Appends a single event to the durable log identified by {@code streamId}, honouring the
     * per-stream ordering + optimistic-concurrency contract (ADR-049).
     *
     * <p>Synchronous on the contract surface — the call returns only after the binding has
     * accepted ownership of the payload and assigned the committed sequence (Kafka producer
     * enqueue + sequence, JDBC outbox INSERT commit, etc.). Implementations MAY perform the
     * actual broker round-trip asynchronously off the caller thread, but the returned
     * {@link AppendResult} MUST reflect the committed per-stream sequence.
     *
     * @param streamId        target stream; {@link StreamId#streamIdHigh()} /
     *                        {@link StreamId#streamIdLow()} MUST match the stream UUID carried by
     *                        {@code descriptor}
     * @param expectedVersion the stream head the caller expects ({@code >= 0}), or
     *                        {@link #ANY_VERSION} to skip the concurrency check; negative values
     *                        other than {@link #ANY_VERSION} are reserved and their behaviour is
     *                        undefined
     * @param descriptor      routing metadata (non-null)
     * @param payload         event payload; ownership transfers to the appender (non-null;
     *                        use {@link EventPayload#empty()} for no-data events)
     * @return the committed per-stream sequence assigned to this event (the stream's new head)
     * @throws NullPointerException if {@code streamId}, {@code descriptor}, or {@code payload} is null
     * @throws eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException
     *         when {@code expectedVersion != }{@link #ANY_VERSION} and it does not match the
     *         stream's current head ({@code EX-EVENT-6008}); the head is left unchanged
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         when the binding cannot accept the append (broker disconnected, outbox INSERT
     *         rejected, etc.)
     */
    AppendResult append(StreamId streamId, long expectedVersion,
                        EventDescriptor descriptor, EventPayload payload);
}
