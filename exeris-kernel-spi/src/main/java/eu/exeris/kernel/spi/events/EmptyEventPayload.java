/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import java.lang.foreign.MemorySegment;

/**
 * Package-private sentinel implementation of {@link EventPayload} for zero-data events.
 *
 * <p>Accessed via {@link EventPayload#empty()}. Immutable singleton —
 * {@link #retain()} and {@link #close()} are no-ops. {@link #refCount()} returns
 * {@link Integer#MAX_VALUE} to prevent accidental release guards from triggering.
 *
 * <p>Intentionally <b>package-private</b>: no external module should reference it directly.
 * Always obtain via {@link EventPayload#empty()}.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — a single immutable instance over a zero-length
 * segment, shared by every no-data event
 * <p><b>Thread confinement:</b> any thread — it is immutable and its retain/close do nothing, so
 * there is no state for concurrent holders to race on
 * <p><b>Ownership:</b> owned by nobody and released by nobody; it is exempt from the
 * {@link EventPayload} reference-count contract by design
 *
 * @since 0.5
 */
final class EmptyEventPayload implements EventPayload {

    /* default */ static final EmptyEventPayload INSTANCE = new EmptyEventPayload();

    private static final MemorySegment EMPTY_SEGMENT =
            MemorySegment.ofArray(new byte[0]);

    private EmptyEventPayload() {}

    @Override
    public MemorySegment segment() {
        return EMPTY_SEGMENT;
    }

    @Override
    public int length() {
        return 0;
    }

    @Override
    public void retain() {
        /* immortal sentinel — no-op */
    }

    @Override
    public void close() {
        /* no backing memory — no-op */
    }

    @Override
    public int refCount() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public String toString() {
        return "EventPayload.empty()";
    }
}

