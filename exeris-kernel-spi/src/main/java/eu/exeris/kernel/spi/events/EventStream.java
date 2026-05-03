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
 * on hand-off. Callers MUST call {@link EventPayload#close()} on every payload they
 * consume — typically via {@code try-with-resources} on the payload itself. The bus's
 * broadcast retain protocol does not apply here: replay delivers single-consumer payloads.
 *
 * <h2>Cursor Lifecycle</h2>
 * <p>The stream owns an underlying cursor (DB result set, broker consumer, etc.).
 * Callers MUST call {@link #close()} when done — typically via {@code try-with-resources}
 * on the {@code EventStream} itself — to release the cursor; failing to do so leaks
 * driver resources. {@code close()} is idempotent.
 *
 * <h2>Allocation Discipline</h2>
 * <p>Implementations SHOULD reuse a single {@link EventPayload} carrier per
 * {@code Iterator.next()} call — or hand the caller a payload whose backing slab is
 * lazily filled — so the per-event allocation budget on a hot replay path is bounded
 * by the descriptor footprint, not by the payload size.
 *
 * @since 0.7.0
 * @see EventStreamReader
 * @see EventPayload
 */
public interface EventStream extends Iterable<EventPayload>, AutoCloseable {

    /**
     * Releases the underlying cursor (DB result set, broker consumer, etc.).
     * Idempotent. Overridden to remove the {@code throws Exception} clause from
     * {@link AutoCloseable} — implementations MUST NOT raise checked exceptions
     * here; lift any internal failure to a runtime kernel exception (e.g.
     * {@link eu.exeris.kernel.spi.exceptions.events.EventEngineException}).
     */
    @Override
    void close();
}
