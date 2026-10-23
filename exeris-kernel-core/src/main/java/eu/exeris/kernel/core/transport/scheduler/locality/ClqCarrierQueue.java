/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The baseline run queue: one {@link ConcurrentLinkedQueue} that every producer and the owner
 * share. It cannot be stolen from. A length counter is kept only when a placement policy asks for
 * an O(1) length estimate, since {@code ConcurrentLinkedQueue#size()} is linear.
 */
final class ClqCarrierQueue implements CarrierQueue {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger length;

    ClqCarrierQueue(boolean trackLength) {
        this.length = trackLength ? new AtomicInteger() : null;
    }

    @Override
    public void offerRemote(Runnable task) {
        queue.offer(task);
        if (length != null) {
            length.incrementAndGet();
        }
    }

    @Override
    public void offerLocal(Runnable task) {
        offerRemote(task);
    }

    @Override
    public Runnable poll() {
        Runnable task = queue.poll();
        if (task != null && length != null) {
            length.decrementAndGet();
        }
        return task;
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int sizeEstimate() {
        return length != null ? Math.max(0, length.get()) : (queue.isEmpty() ? 0 : 1);
    }

    @Override
    public Runnable steal() {
        return null;
    }
}
