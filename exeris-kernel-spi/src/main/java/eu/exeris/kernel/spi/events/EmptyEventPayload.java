/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.5.0
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

