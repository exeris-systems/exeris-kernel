/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.Thread.VirtualThreadTask;

/**
 * Run queue made of an owner work-stealing deque and an inbox for remote producers.
 *
 * <ul>
 *   <li>Remote producers append to the {@link MpscInbox}; only the owner reads it.</li>
 *   <li>The owner moves a batch from the inbox to its deque when the deque runs dry, and every
 *       {@value #INBOX_TICK}th poll regardless, so a local run of self-wakeups cannot hold remote
 *       arrivals back. Tasks of pinned contexts never enter the deque: they go to an owner-private
 *       FIFO, so a thief cannot see them.</li>
 *   <li>The owner takes the newest local task (last in, first out, which keeps a woken task on
 *       the cache its waker just used). Every {@value #OLDEST_TICK}th poll it takes the oldest
 *       one instead, so no local task waits for ever behind newer ones.</li>
 * </ul>
 */
final class DequeCarrierQueue implements CarrierQueue {

    private static final int INBOX_TICK = 16;
    private static final int OLDEST_TICK = 64;
    private static final int REFILL_BATCH = 64;

    private final WorkStealingDeque deque = new WorkStealingDeque(256);
    private final MpscInbox inbox = new MpscInbox(4096);
    private final Runnable[] scratch = new Runnable[REFILL_BATCH];
    private Runnable[] fifo = new Runnable[16];
    private int fifoHead;
    private int fifoSize;
    private int tick;

    @Override
    public void offerRemote(Runnable task) {
        inbox.offer(task);
    }

    @Override
    public void offerLocal(Runnable task) {
        if (isPinned(task)) {
            fifoAdd(task);
        } else {
            deque.push(task);
        }
    }

    @Override
    public Runnable poll() {
        tick++;
        if ((tick & (INBOX_TICK - 1)) == 0) {
            refill();
        }
        if ((tick & (OLDEST_TICK - 1)) == 0) {
            Runnable oldest = deque.steal();
            if (oldest != null) {
                return oldest;
            }
        }
        Runnable task = deque.pop();
        if (task != null) {
            return task;
        }
        task = fifoPoll();
        if (task != null) {
            return task;
        }
        if (refill()) {
            task = deque.pop();
            return task != null ? task : fifoPoll();
        }
        return null;
    }

    @Override
    public boolean isEmpty() {
        return deque.isEmpty() && fifoSize == 0 && inbox.isEmpty();
    }

    @Override
    public int sizeEstimate() {
        return deque.size() + inbox.sizeEstimate() + fifoSize;
    }

    @Override
    public Runnable steal() {
        return deque.steal();
    }

    /**
     * Moves up to a batch from the inbox: pinned tasks to the private FIFO, the rest to the deque
     * in reverse, so that popping the newest end serves the batch in arrival order.
     */
    private boolean refill() {
        int n = 0;
        Runnable task;
        while (n < REFILL_BATCH && (task = inbox.poll()) != null) {
            if (isPinned(task)) {
                fifoAdd(task);
            } else {
                scratch[n++] = task;
            }
        }
        boolean moved = n > 0 || fifoSize > 0;
        for (int i = n - 1; i >= 0; i--) {
            deque.push(scratch[i]);
            scratch[i] = null;
        }
        return moved;
    }

    private void fifoAdd(Runnable task) {
        if (fifoSize == fifo.length) {
            Runnable[] grown = new Runnable[fifo.length << 1];
            for (int i = 0; i < fifoSize; i++) {
                grown[i] = fifo[(fifoHead + i) & (fifo.length - 1)];
            }
            fifo = grown;
            fifoHead = 0;
        }
        fifo[(fifoHead + fifoSize) & (fifo.length - 1)] = task;
        fifoSize++;
    }

    private Runnable fifoPoll() {
        if (fifoSize == 0) {
            return null;
        }
        Runnable task = fifo[fifoHead];
        fifo[fifoHead] = null;
        fifoHead = (fifoHead + 1) & (fifo.length - 1);
        fifoSize--;
        return task;
    }

    static boolean isPinned(Runnable task) {
        return task instanceof VirtualThreadTask vt && vt.attachment() instanceof CarrierSchedulingContext ctx
                && ctx.pinned();
    }
}
