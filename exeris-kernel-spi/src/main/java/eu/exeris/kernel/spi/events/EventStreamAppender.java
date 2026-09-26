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
 * by the bootstrapper before {@link EventEngine#start()}.
 *
 * <h2>Sequencing &amp; Optimistic Concurrency (ADR-049)</h2>
 * <p>The durable log is the kernel's ordering / optimistic-concurrency boundary. This contract is
 * what log-derived views (event sourcing, KV-as-projection, the replicated distributed log) rely
 * on; it is owned <b>here</b>, on the Events SPI — not by the transient {@link EventBus}
 * (unordered by design) and not by Persistence. The separate
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
 * <p><b>Thread confinement:</b> any thread — concurrent appends to one {@link StreamId} are
 * linearized by the binding rather than rejected, so callers need no external lock per stream
 * <p><b>Ownership:</b> {@link #append} takes the caller's payload reference on entry, exactly as
 * {@link EventBus#publish} does; the caller does not close it afterwards, on success or on
 * failure
 *
 * @implSpec Per <b>ADR-049</b>, every binding provides a <b>per-stream total order</b>:
 *           concurrent appends to the same {@link StreamId} are linearized and assigned strictly
 *           monotonic, 1-based sequence numbers, and
 *           {@link EventStreamReader#replayFromVersion(StreamId, long)} reads them back in that
 *           order. The optimistic-concurrency check fails closed — a mismatch appends nothing and
 *           leaves the head where it was. Bindings realize both privately, so no broker or JDBC
 *           type reaches this contract.
 * @apiNote The slot may be unbound: a kernel whose broker offers no durable-append capability
 *          binds nothing here. Treat that as "capability absent" and fall back to the
 *          {@link EventBus} dispatch path — it is not a hard error. Direct use of this SPI is for
 *          sites that need topic or partition control; most callers route through the
 *          transactional outbox instead.
 * @since 0.7
 * @see EventStreamReader
 * @see AppendResult
 */
@FunctionalInterface
public interface EventStreamAppender {

    /**
     * Sentinel {@code expectedVersion} for {@link #append} that skips the optimistic-concurrency
     * check — the event is appended unconditionally at the stream head, and an append made with
     * it never raises
     * {@link eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException}. For
     * append-only producers that do not enforce per-stream compare-and-set.
     */
    long ANY_VERSION = -1L;

    /**
     * Commits a single event to the durable log identified by {@code streamId}, taking a position
     * in that stream's total order — or refusing, if another writer took the position first.
     *
     * <p>Synchronous on the contract surface — the call returns only after the binding has
     * accepted ownership of the payload and assigned the committed sequence.
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
     *         {@code EX-EVENT-6008} when {@code expectedVersion != }{@link #ANY_VERSION} and it
     *         does not match the stream's current head; the head is left unchanged and
     *         {@code rawArgs} carry
     *         {@code [String streamType, long expectedVersion, long actualVersion]}
     * @throws eu.exeris.kernel.spi.exceptions.events.EventEngineException
     *         {@code EX-EVENT-6001} when the binding cannot accept the append at all (broker
     *         disconnected, outbox INSERT rejected), unless the binding raises a more specific
     *         {@code EX-EVENT-*} code
     * @implSpec The binding may perform the broker round-trip asynchronously off the caller
     *           thread, but the {@link AppendResult} it returns reflects the sequence the event
     *           actually committed at — never a provisional or predicted one.
     * @apiNote On {@code EX-EVENT-6008} the caller re-reads the stream (through
     *          {@link EventStreamReader#replayFromVersion(StreamId, long)}) and retries against
     *          the head it observes; retrying with the same {@code expectedVersion} conflicts
     *          again.
     */
    AppendResult append(StreamId streamId, long expectedVersion,
                        EventDescriptor descriptor, EventPayload payload);
}
