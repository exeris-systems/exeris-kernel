/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * SPI: streamed iterator over events returned by {@link EventStreamReader}.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>Implementation-blind. The community binding may be backed by a JDBC cursor
 * (PostgreSQL WAL replay), the Kafka driver may be backed by a partitioned consumer
 * seeking from offset/timestamp; neither leaks here. Callers see only an
 * {@code Iterable<EventPayload>} that releases the underlying cursor on {@link #close()}.
 *
 * <h2>RAII Per-Payload Lifecycle</h2>
 * <p>Each {@link EventPayload} returned by the iterator has reference count {@code 1}
 * on hand-off. The bus's broadcast retain protocol does not apply here: replay delivers
 * single-consumer payloads, so there is exactly one holder and exactly one close.
 *
 * <p><b>Allocation:</b> allocates (one payload per iteration step, bounded to that event's own
 * bytes; the payloads are never accumulated into a {@code List<EventPayload>} ahead of the
 * consumer)
 * <p><b>Ownership:</b> the caller closes every payload the iterator yields, and closes the stream
 * itself to release the cursor; the two are separate obligations and neither implies the other
 *
 * @implSpec {@link #close()} releases the underlying cursor and is idempotent. Payloads are
 *           handed over at reference count {@code 1}. An implementation allocates at most one
 *           payload per {@code Iterator.next()} call, bounded to that event's own bytes; payloads
 *           are not accumulated ahead of the consumer.
 * @apiNote Put {@code try-with-resources} on the {@code EventStream} and on each payload:
 *          skipping the stream's own close leaks a driver cursor, and skipping a payload's close
 *          leaks its slab on a pooled binding.
 * @implNote Neither Community binding streams off-heap slabs. The JDBC binding iterates lazily
 *           over a live cursor; the Kafka binding reads to the end of the topic and materialises
 *           the matching frames on heap before iterating.
 * @since 0.7
 * @see EventStreamReader
 * @see EventPayload
 */
public interface EventStream extends Iterable<EventPayload>, AutoCloseable {

    /**
     * Releases the underlying cursor (DB result set, broker consumer, etc.), ending the replay
     * whether or not the iterator was exhausted.
     *
     * @implSpec Idempotent, and declared without the {@code throws Exception} clause
     *           {@link AutoCloseable} would otherwise impose: an implementation raises no checked
     *           exception here, lifting an internal failure to a runtime kernel exception such as
     *           {@link eu.exeris.kernel.spi.exceptions.events.EventEngineException} instead.
     * @apiNote Closing the stream does not close the payloads already handed out — those remain
     *          the consumer's to release.
     */
    @Override
    void close();
}
