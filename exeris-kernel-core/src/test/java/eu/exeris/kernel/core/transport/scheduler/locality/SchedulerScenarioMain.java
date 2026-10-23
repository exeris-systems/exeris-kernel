/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.Thread.VirtualThreadTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Entry point of the JVM that {@link ForkedSchedulerRunner} launches with the locality scheduler
 * installed through {@code -Djdk.virtualThreadScheduler.implClass}. Surefire's own JVM carries
 * stub scheduler interfaces and never runs a virtual thread on a carrier of this scheduler, so every
 * assertion about real scheduling is made here and reported back as {@code key=value} lines.
 */
public final class SchedulerScenarioMain {

    private static final int STREAMS = 24;
    private static final int EXCHANGES = 200;
    private static final long TIMEOUT_SECONDS = 90;

    private SchedulerScenarioMain() {
    }

    public static void main(String[] args) throws Exception {
        ExerisCarrierGroup group = ExerisCarrierScheduler.group();
        require(group != null, "the locality scheduler is not installed");
        switch (args[0]) {
            case "smoke" -> smoke(group);
            case "resolve" -> resolve(group);
            case "poller" -> poller(group);
            case "stress" -> stress(group);
            case "stats" -> stats(group);
            case "idle" -> idle();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.flush();
        System.exit(0);
    }

    static int mountedId() {
        ExerisCarrierThread carrier = CarrierResolver.mounted();
        return carrier == null ? -1 : carrier.id();
    }

    private static void smoke(ExerisCarrierGroup group) throws Exception {
        ExerisCarrierScheduler.resetTimerStats();
        RoundRobinCarrierExecutionBackend backend = new RoundRobinCarrierExecutionBackend(group);
        CountDownLatch done = new CountDownLatch(STREAMS);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger stable = new AtomicInteger();
        AtomicInteger onCustom = new AtomicInteger();
        AtomicInteger childOnCustom = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Exchanger<Integer>[] pairs = newExchangers(STREAMS / 2);
        for (int i = 0; i < STREAMS; i++) {
            final int index = i;
            backend.start("stream-" + i, () -> {
                try {
                    int expected = index % group.size();
                    int first = mountedId();
                    gate.await();
                    LockSupport.parkNanos(50_000);
                    for (int r = 0; r < EXCHANGES; r++) {
                        pairs[index / 2].exchange(r);
                    }
                    int second = mountedId();
                    int[] child = new int[1];
                    Thread t = Thread.ofVirtual().unstarted(() -> child[0] = mountedId());
                    t.start();
                    t.join();
                    int third = mountedId();
                    if (first >= 0 && second >= 0 && third >= 0) {
                        onCustom.incrementAndGet();
                    }
                    if (first == expected && second == expected && third == expected) {
                        stable.incrementAndGet();
                    }
                    if (child[0] >= 0) {
                        childOnCustom.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        require(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "streams did not complete");
        require(failure.get() == null, "stream failed: " + failure.get());
        ExerisCarrierScheduler.TimerStats timers = ExerisCarrierScheduler.timerStats();
        System.out.println("RESULT streams=" + STREAMS + " onCustom=" + onCustom.get()
                + " stable=" + stable.get() + " childOnCustom=" + childOnCustom.get()
                + " timersScheduled=" + timers.scheduled() + " timersCancelled=" + timers.cancelled());
    }

    /**
     * Waves of yielding virtual threads, all started on carrier 0, until the stealing carriers
     * have taken work and the idle ones have parked; then every counter is printed on one
     * {@code STATS} line.
     */
    private static void stats(ExerisCarrierGroup group) throws Exception {
        ExerisCarrierScheduler.resetStats();
        boolean stealing = Boolean.getBoolean("exeris.locality.steal");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        do {
            runWave(group, 400);
            require(System.nanoTime() < deadline, "no steal or park observed: " + ExerisCarrierScheduler.statsSnapshot());
        } while (ExerisCarrierScheduler.statsSnapshot().get("idle.parks") == 0
                || stealing && ExerisCarrierScheduler.statsSnapshot().get("steal.tasks") == 0);
        StringBuilder line = new StringBuilder("STATS");
        ExerisCarrierScheduler.statsSnapshot().forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
        System.out.println(line);
        System.out.println("RESULT done=1");
    }

    private static void runWave(ExerisCarrierGroup group, int threads) throws Exception {
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            group.carrier(0).virtualThreadFactory().newThread(() -> {
                for (int r = 0; r < 50; r++) {
                    Thread.yield();
                }
                done.countDown();
            }).start();
        }
        require(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "wave did not complete");
    }

    /** Prints the snapshot size; the scheduler is installed but its counters are off. */
    private static void idle() {
        System.out.println("RESULT snapshotSize=" + ExerisCarrierScheduler.statsSnapshot().size());
    }

    @SuppressWarnings("unchecked")
    private static Exchanger<Integer>[] newExchangers(int n) {
        Exchanger<Integer>[] result = new Exchanger[n];
        for (int i = 0; i < n; i++) {
            result[i] = new Exchanger<>();
        }
        return result;
    }

    /** One line per caller kind: what each resolution mode makes of it. */
    private static void resolve(ExerisCarrierGroup group) throws Exception {
        RoundRobinCarrierExecutionBackend backend = new RoundRobinCarrierExecutionBackend(group);
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder factory = new StringBuilder();
        backend.start("resolve-factory", () -> {
            ExerisCarrierThread scope = ExerisCarrierThread.currentCarrier();
            factory.append("factoryMounted=").append(mountedId())
                    .append(" factoryScope=").append(scope == null ? -1 : scope.id());
            done.countDown();
        });
        require(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "factory stream did not complete");
        int[] plain = new int[1];
        Thread t = Thread.ofVirtual().unstarted(() -> plain[0] = mountedId());
        t.start();
        t.join();
        CountDownLatch carrierRan = new CountDownLatch(1);
        int[] onCarrier = new int[1];
        group.carrier(1).enqueue(() -> {
            onCarrier[0] = mountedId();
            carrierRan.countDown();
        });
        require(carrierRan.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "carrier task did not run");
        System.out.println("RESULT " + factory + " plainVirtualMounted=" + plain[0]
                + " carrierPlatform=" + onCarrier[0] + " foreignPlatform=" + mountedId()
                + " jlaAvailable=" + CarrierResolver.mountedCarrierAvailable());
    }

    /** A thread started with a preferred carrier by a starter that is not a factory thread. */
    private static void poller(ExerisCarrierGroup group) throws Exception {
        ExerisCarrierScheduler scheduler = ExerisCarrierScheduler.instance();
        ExerisCarrierThread target = group.carrier(group.size() - 1);
        int[] ranOn = {-2};
        AtomicReference<VirtualThreadTask> task = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread starter = Thread.ofVirtual().unstarted(() -> {
            VirtualThreadTask created = scheduler.newThread(Thread.ofVirtual(), target.carrierThread(),
                    () -> {
                        ranOn[0] = mountedId();
                        done.countDown();
                    });
            task.set(created);
            created.thread().start();
        });
        starter.start();
        require(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "preferred-carrier thread did not run");
        starter.join();
        boolean pinned = task.get().attachment() instanceof CarrierSchedulingContext ctx && ctx.pinned();
        System.out.println("RESULT target=" + target.id() + " ranOn=" + ranOn[0] + " pinned=" + pinned);
    }

    /**
     * Wakeups from mounted virtual threads (a token passed around a ring) and from a foreign
     * platform thread (a ticket handed to every waiter in turn). A lost wakeup leaves a waiter
     * parked and the scenario fails by timeout.
     */
    private static void stress(ExerisCarrierGroup group) throws Exception {
        final int ring = 8;
        final int rounds = 3000;
        final int total = ring * rounds;
        AtomicInteger token = new AtomicInteger();
        Thread[] ringThreads = new Thread[ring];
        for (int i = 0; i < ring; i++) {
            final int self = i;
            ringThreads[i] = group.carrier(i).virtualThreadFactory().newThread(() -> {
                for (int r = 0; r < rounds; r++) {
                    while (token.get() % ring != self) {
                        LockSupport.park();
                    }
                    int next = token.incrementAndGet();
                    LockSupport.unpark(ringThreads[next % ring]);
                }
            });
        }
        for (Thread t : ringThreads) {
            t.start();
        }
        for (Thread t : ringThreads) {
            require(t.join(java.time.Duration.ofSeconds(TIMEOUT_SECONDS)), "ring did not complete, token=" + token.get());
        }
        require(token.get() == total, "ring token " + token.get());

        final int waiters = 16;
        final int tickets = 2000;
        AtomicInteger go = new AtomicInteger();
        AtomicInteger acks = new AtomicInteger();
        Thread[] parked = new Thread[waiters];
        for (int i = 0; i < waiters; i++) {
            parked[i] = group.carrier(i).virtualThreadFactory().newThread(() -> {
                for (int k = 1; k <= tickets; k++) {
                    while (go.get() < k) {
                        LockSupport.park();
                    }
                    acks.incrementAndGet();
                }
            });
            parked[i].start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        for (int k = 1; k <= tickets; k++) {
            go.set(k);
            for (Thread t : parked) {
                LockSupport.unpark(t);
            }
            while (acks.get() < k * waiters) {
                require(System.nanoTime() < deadline, "waiters stalled at ticket " + k + " acks=" + acks.get());
                Thread.onSpinWait();
            }
        }
        System.out.println("RESULT ring=" + token.get() + " acks=" + acks.get());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            System.out.println("FAILED " + message);
            System.out.flush();
            System.exit(2);
        }
    }
}
