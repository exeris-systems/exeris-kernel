/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.Thread.VirtualThreadTask;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Work-stealing deque, MPSC inbox and the deque run queue")
class CarrierQueueConcurrencyTest {

    private static final long WAIT_SECONDS = 60;

    /** A task that records how many times it was taken, by id. */
    private record Ticket(int id) implements Runnable {
        @Override
        public void run() {
            // identity only
        }
    }

    @Test
    @DisplayName("deque: the owner pops newest first, a thief takes oldest first, and it grows past its capacity")
    void dequeOrderAndGrowth() {
        WorkStealingDeque deque = new WorkStealingDeque(4);
        Ticket[] tickets = new Ticket[100];
        for (int i = 0; i < tickets.length; i++) {
            tickets[i] = new Ticket(i);
            deque.push(tickets[i]);
        }
        assertEquals(100, deque.size());
        assertSame(tickets[0], deque.steal());
        assertSame(tickets[1], deque.steal());
        assertSame(tickets[99], deque.pop());
        assertSame(tickets[98], deque.pop());
        assertEquals(96, deque.size());
        for (int i = 97; i >= 2; i--) {
            assertSame(tickets[i], deque.pop());
        }
        assertNull(deque.pop());
        assertNull(deque.steal());
        assertTrue(deque.isEmpty());
    }

    @Test
    @DisplayName("deque: under a concurrent owner and thieves no task is lost or taken twice")
    void dequeNoLossNoDuplicate() throws Exception {
        final int total = 300_000;
        final int thieves = 3;
        WorkStealingDeque deque = new WorkStealingDeque(4);
        AtomicIntegerArray taken = new AtomicIntegerArray(total);
        AtomicInteger takenCount = new AtomicInteger();
        AtomicBoolean ownerDone = new AtomicBoolean();
        CountDownLatch thievesDone = new CountDownLatch(thieves);
        for (int i = 0; i < thieves; i++) {
            Thread thief = new Thread(() -> {
                while (!ownerDone.get() || takenCount.get() < total) {
                    Runnable r = deque.steal();
                    if (r != null) {
                        taken.incrementAndGet(((Ticket) r).id());
                        takenCount.incrementAndGet();
                    } else if (takenCount.get() >= total) {
                        break;
                    }
                }
                thievesDone.countDown();
            });
            thief.setDaemon(true);
            thief.start();
        }
        int pushed = 0;
        while (pushed < total) {
            int burst = Math.min(total - pushed, 1 + (pushed % 7) * 5);
            for (int i = 0; i < burst; i++) {
                deque.push(new Ticket(pushed++));
            }
            for (int i = 0; i < burst / 2; i++) {
                Runnable r = deque.pop();
                if (r != null) {
                    taken.incrementAndGet(((Ticket) r).id());
                    takenCount.incrementAndGet();
                }
            }
        }
        ownerDone.set(true);
        Runnable r;
        while ((r = deque.pop()) != null) {
            taken.incrementAndGet(((Ticket) r).id());
            takenCount.incrementAndGet();
        }
        assertTrue(thievesDone.await(WAIT_SECONDS, TimeUnit.SECONDS), "thieves did not finish");
        assertAllTakenOnce(taken, total);
    }

    @Test
    @DisplayName("inbox: concurrent producers through a tiny ring and its overflow lose nothing")
    void inboxNoLossNoDuplicate() throws Exception {
        final int producers = 4;
        final int perProducer = 100_000;
        final int total = producers * perProducer;
        MpscInbox inbox = new MpscInbox(8);
        AtomicIntegerArray taken = new AtomicIntegerArray(total);
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            Thread producer = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    inbox.offer(new Ticket(base + i));
                }
            });
            producer.setDaemon(true);
            producer.start();
        }
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (received < total) {
            Runnable r = inbox.poll();
            if (r != null) {
                taken.incrementAndGet(((Ticket) r).id());
                received++;
            } else {
                assertTrue(System.nanoTime() < deadline, "inbox stalled at " + received + " of " + total);
                Thread.onSpinWait();
            }
        }
        assertTrue(inbox.isEmpty());
        assertAllTakenOnce(taken, total);
    }

    @Test
    @DisplayName("inbox: a claimed place whose slot is not yet filled counts as non-empty and is waited for")
    void inboxClaimedButUnpublishedIsNotEmpty() throws Throwable {
        MpscInbox inbox = new MpscInbox(8);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(MpscInbox.class, MethodHandles.lookup());
        VarHandle producerIndex = lookup.findVarHandle(MpscInbox.class, "producerIndex", long.class);
        VarHandle ring = MethodHandles.arrayElementVarHandle(Object[].class);
        Object[] slots = (Object[]) lookup.findVarHandle(MpscInbox.class, "ring", Object[].class).get(inbox);

        assertTrue(inbox.isEmpty());
        producerIndex.getAndAdd(inbox, 1L);
        assertFalse(inbox.isEmpty(), "the producer index advanced: a producer is mid-publication");

        AtomicReference<Runnable> polled = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            polled.set(inbox.poll());
            done.countDown();
        });
        consumer.setDaemon(true);
        consumer.start();
        Ticket published = new Ticket(7);
        ring.setRelease(slots, 0, published);
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(published, polled.get());
        assertTrue(inbox.isEmpty());
    }

    @Test
    @DisplayName("deque queue: remote producers, the owner and thieves together lose and duplicate nothing")
    void dequeQueueNoLossNoDuplicate() throws Exception {
        final int producers = 3;
        final int perProducer = 60_000;
        final int total = producers * perProducer * 2;
        DequeCarrierQueue queue = new DequeCarrierQueue();
        AtomicIntegerArray taken = new AtomicIntegerArray(total);
        AtomicInteger takenCount = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> helpers = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer * 2;
            helpers.add(new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    queue.offerRemote(new Ticket(base + 2 * i));
                }
            }));
        }
        for (int t = 0; t < 2; t++) {
            helpers.add(new Thread(() -> {
                while (!stop.get()) {
                    Runnable r = queue.steal();
                    if (r != null) {
                        int id = ((Ticket) r).id();
                        taken.incrementAndGet(id);
                        takenCount.incrementAndGet();
                        if ((id & 1) == 0) {
                            queue.offerRemote(new Ticket(id + 1));
                        }
                    } else {
                        Thread.onSpinWait();
                    }
                }
            }));
        }
        helpers.forEach(h -> {
            h.setDaemon(true);
            h.start();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        // the owner: every remote task it runs makes one local task, as a woken task does
        while (takenCount.get() < total) {
            assertTrue(System.nanoTime() < deadline, "queue stalled at " + takenCount.get() + " of " + total);
            Runnable r = queue.poll();
            if (r == null) {
                Thread.onSpinWait();
                continue;
            }
            int id = ((Ticket) r).id();
            taken.incrementAndGet(id);
            takenCount.incrementAndGet();
            if ((id & 1) == 0) {
                queue.offerLocal(new Ticket(id + 1));
            }
        }
        stop.set(true);
        assertAllTakenOnce(taken, total);
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("deque queue: tasks of a pinned context are never stolen, from either side")
    void pinnedTasksAreNotStealable() throws Exception {
        DequeCarrierQueue queue = new DequeCarrierQueue();
        CarrierSchedulingContext pinnedContext = new CarrierSchedulingContext(0, null, true);
        final int pinnedCount = 5_000;
        final int plainCount = 5_000;
        AtomicInteger stolenPinned = new AtomicInteger();
        AtomicInteger stolen = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        Thread thief = new Thread(() -> {
            while (!stop.get()) {
                Runnable r = queue.steal();
                if (r instanceof PinnedTask) {
                    stolenPinned.incrementAndGet();
                } else if (r != null) {
                    stolen.incrementAndGet();
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        thief.setDaemon(true);
        thief.start();
        for (int i = 0; i < pinnedCount; i++) {
            if ((i & 1) == 0) {
                queue.offerRemote(new PinnedTask(pinnedContext));
            } else {
                queue.offerLocal(new PinnedTask(pinnedContext));
            }
            queue.offerRemote(new Ticket(i));
        }
        for (int i = 0; i < plainCount; i++) {
            queue.offerLocal(new Ticket(i));
        }
        int ownerPinned = 0;
        int ownerPlain = 0;
        final int plainTotal = pinnedCount + plainCount;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (ownerPinned < pinnedCount || ownerPlain + stolen.get() < plainTotal) {
            assertTrue(System.nanoTime() < deadline, "owner stalled");
            Runnable r = queue.poll();
            if (r instanceof PinnedTask) {
                ownerPinned++;
            } else if (r != null) {
                ownerPlain++;
            } else {
                Thread.onSpinWait();
            }
        }
        stop.set(true);
        assertEquals(0, stolenPinned.get(), "a pinned task was stolen");
        assertEquals(pinnedCount, ownerPinned);
        assertEquals(plainTotal, ownerPlain + stolen.get());
    }

    private static void assertAllTakenOnce(AtomicIntegerArray taken, int total) {
        for (int i = 0; i < total; i++) {
            int n = taken.get(i);
            if (n != 1) {
                throw new AssertionError("task " + i + " was taken " + n + " times");
            }
        }
    }

    /** A virtual-thread task whose attachment is a pinned context. */
    private static final class PinnedTask implements VirtualThreadTask {
        private final CarrierSchedulingContext context;

        PinnedTask(CarrierSchedulingContext context) {
            this.context = context;
        }

        @Override
        public Thread thread() {
            return null;
        }

        @Override
        public void run() {
            // identity only
        }

        @Override
        public Thread preferredCarrier() {
            return null;
        }

        @Override
        public Object attach(Object obj) {
            return null;
        }

        @Override
        public Object attachment() {
            return context;
        }
    }
}
