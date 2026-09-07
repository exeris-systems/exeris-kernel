/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A heap-backed {@link EventPayload} carrying one flow's terminal-state progress record —
 * {@code definitionName|stepIndex|state}, UTF-8 encoded.
 *
 * <p><b>Allocation:</b> allocates once, at construction — the encoded {@code byte[]} backing
 * this payload; {@link #retain()} and {@link #close()} allocate nothing.
 * <p><b>Thread confinement:</b> any thread — {@link #retain()} and {@link #close()} are a CAS
 * loop on the reference count rather than a lock, so a reference may cross a thread boundary
 * without external synchronization.
 * <p><b>Ownership:</b> whoever holds a reference owes exactly one {@link #close()} for it; a
 * {@link #close()} once the count has already reached zero is a no-op rather than an error,
 * matching the Community binding {@link EventPayload} describes.
 *
 * @implNote The backing bytes are exposed through {@link MemorySegment#ofArray(byte[])} with no
 *           copy.
 */
final class FlowProgressPayload implements EventPayload {

    private final byte[] bytes;
    private final MemorySegment segment;
    private final AtomicInteger refCount = new AtomicInteger(1);

    /**
     * Encodes the progress record and wraps it as a payload with an initial reference count of 1.
     *
     * @param definitionName the flow definition name to record
     * @param stepIndex      the step index to record
     * @param state          the terminal {@link FlowState} to record
     */
    /* default */ FlowProgressPayload(String definitionName, int stepIndex, FlowState state) {
        this.bytes = (definitionName + '|' + stepIndex + '|' + state.name()).getBytes(StandardCharsets.UTF_8);
        this.segment = MemorySegment.ofArray(bytes);
    }

    @Override
    public MemorySegment segment() {
        if (!isAlive()) {
            throw new IllegalStateException("Flow progress payload has already been released");
        }
        return segment;
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public void retain() {
        while (true) {
            int current = refCount.get();
            if (current == 0) {
                throw new IllegalStateException("Flow progress payload has already been released");
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    @Override
    public void close() {
        while (true) {
            int current = refCount.get();
            if (current == 0) {
                return;
            }
            if (refCount.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public boolean isAlive() {
        return refCount.get() > 0;
    }
}
