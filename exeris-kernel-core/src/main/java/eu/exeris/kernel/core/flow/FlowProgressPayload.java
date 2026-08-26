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

final class FlowProgressPayload implements EventPayload {

    private final byte[] bytes;
    private final MemorySegment segment;
    private final AtomicInteger refCount = new AtomicInteger(1);

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
