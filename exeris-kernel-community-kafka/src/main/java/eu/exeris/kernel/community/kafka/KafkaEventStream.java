/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStream;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * {@link EventStream} over a materialized, already-ordered list of Kafka event-log frames (ADR-049).
 *
 * <p>Kafka replay is a bounded read-to-end (see {@link KafkaEventLog}), so the frames are fetched
 * eagerly and this stream lazily decodes one payload per {@link Iterator#next()} — each
 * {@link KafkaHeapEventPayload} arrives at refCount 1 and the consumer owns its close. The backing
 * frames are heap {@code byte[]} (GC-managed), so {@link #close()} holds no live cursor and is a
 * safe, idempotent no-op.
 *
 * @since 0.10.0
 */
final class KafkaEventStream implements EventStream {

    private final List<byte[]> frames;

    /* default */ KafkaEventStream(List<byte[]> frames) {
        this.frames = frames;
    }

    @Override
    public Iterator<EventPayload> iterator() {
        return new FrameIterator(frames.iterator());
    }

    @Override
    public void close() {
        // No live cursor to release: frames are materialized heap byte[], reclaimed by GC.
        // Idempotent no-op by construction.
    }

    private static final class FrameIterator implements Iterator<EventPayload> {

        private final Iterator<byte[]> delegate;

        private FrameIterator(Iterator<byte[]> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public EventPayload next() {
            if (!delegate.hasNext()) {
                throw new NoSuchElementException("event stream exhausted");
            }
            return KafkaHeapEventPayload.wrap(KafkaEventLogCodec.decodePayloadSegment(delegate.next()));
        }
    }
}
