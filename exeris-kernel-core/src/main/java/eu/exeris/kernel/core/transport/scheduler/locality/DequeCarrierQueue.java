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
 *   <li>Optionally the pinned FIFO is served ahead of everything else, and a single "run next"
 *       slot holds the task a carrier woke itself: it runs next, at most once in a row, and is not
 *       visible to thieves.</li>
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
    private final boolean pollersFirst;
    private final boolean lifoSlot;
    private final boolean stats;
    private final CarrierCounters counters;
    private Runnable slot;
    private boolean slotJustRan;
    private int tick;

    DequeCarrierQueue() {
        this(false, false);
    }

    DequeCarrierQueue(boolean pollersFirst, boolean lifoSlot) {
        this(pollersFirst, lifoSlot, null);
    }

    /** {@code counters} is non-null only while stats are on; the owner's slot hits are counted there. */
    DequeCarrierQueue(boolean pollersFirst, boolean lifoSlot, CarrierCounters counters) {
        this.pollersFirst = pollersFirst;
        this.lifoSlot = lifoSlot;
        this.stats = counters != null;
        this.counters = counters;
    }

    @Override
    public void offerRemote(Runnable task) {
        inbox.offer(task);
    }

    @Override
    public void offerLocal(Runnable task) {
        if (isPinned(task)) {
            fifoAdd(task);
        } else if (lifoSlot) {
            Runnable displaced = slot;
            slot = task;
            if (displaced != null) {
                deque.push(displaced);
            }
        } else {
            deque.push(task);
        }
    }

    @Override
    public Runnable poll() {
        if (!lifoSlot) {
            return pollQueues();
        }
        if (slot != null && !slotJustRan) {
            return takeSlot();
        }
        Runnable task = pollQueues();
        if (task == null && slot != null) {
            return takeSlot();
        }
        slotJustRan = false;
        return task;
    }

    private Runnable takeSlot() {
        Runnable task = slot;
        slot = null;
        slotJustRan = true;
        if (stats) {
            counters.inc(CarrierCounters.LIFO_HITS);
        }
        return task;
    }

    private Runnable pollQueues() {
        tick++;
        if ((tick & (INBOX_TICK - 1)) == 0) {
            refill();
        }
        if (pollersFirst) {
            Runnable poller = fifoPoll();
            if (poller != null) {
                return poller;
            }
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
            task = pollersFirst ? fifoPoll() : null;
            if (task == null) {
                task = deque.pop();
            }
            return task != null ? task : fifoPoll();
        }
        return null;
    }

    @Override
    public boolean isEmpty() {
        return slot == null && deque.isEmpty() && fifoSize == 0 && inbox.isEmpty();
    }

    @Override
    public int sizeEstimate() {
        return deque.size() + inbox.sizeEstimate() + fifoSize + (slot != null ? 1 : 0);
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
