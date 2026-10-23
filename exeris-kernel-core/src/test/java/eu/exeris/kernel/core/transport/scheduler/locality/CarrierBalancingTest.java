/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.Thread.VirtualThreadTask;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Carrier balancing: stealing and power-of-two placement")
class CarrierBalancingTest {

    private static final long WAIT_SECONDS = 60;

    /** A virtual-thread task stand-in that records which carrier ran it and what its context said. */
    private static final class RecordingTask implements VirtualThreadTask {
        private final int index;
        private final CarrierSchedulingContext context;
        private final AtomicIntegerArray ranOn;
        private final AtomicIntegerArray homeWhenRun;
        private final CountDownLatch blocked;
        private final CountDownLatch gate;
        private final CountDownLatch completed;

        RecordingTask(int index, CarrierSchedulingContext context, AtomicIntegerArray ranOn,
                      AtomicIntegerArray homeWhenRun, CountDownLatch blocked, CountDownLatch gate,
                      CountDownLatch completed) {
            this.index = index;
            this.context = context;
            this.ranOn = ranOn;
            this.homeWhenRun = homeWhenRun;
            this.blocked = blocked;
            this.gate = gate;
            this.completed = completed;
        }

        @Override
        public void run() {
            ExerisCarrierThread runner = CarrierResolver.mounted();
            ranOn.set(index, runner.id());
            homeWhenRun.set(index, context.carrier().id());
            blocked.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completed.countDown();
        }

        @Override
        public Thread thread() {
            return null;
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

    @ParameterizedTest(name = "batch={0}")
    @ValueSource(strings = {"half", "3"})
    @DisplayName("a stolen task's context names the carrier that ran it; pinned contexts never leave home")
    void stolenTasksAreRehomedAndPinnedAreNot(String batch) throws Exception {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.of(Map.of(
                "exeris.locality.queue", "deque",
                "exeris.locality.steal", "true",
                "exeris.locality.steal.batch", batch)), 4);
        try {
            ExerisCarrierGroup group = scheduler.carrierGroup();
            ExerisCarrierThread home = group.carrier(0);
            final int n = 96;
            AtomicIntegerArray ranOn = new AtomicIntegerArray(n);
            AtomicIntegerArray homeWhenRun = new AtomicIntegerArray(n);
            CountDownLatch blocked = new CountDownLatch(2);
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(n);
            CarrierSchedulingContext[] contexts = new CarrierSchedulingContext[n];
            RecordingTask[] tasks = new RecordingTask[n];
            for (int i = 0; i < n; i++) {
                contexts[i] = new CarrierSchedulingContext(home.id(), home, i % 3 == 0);
                tasks[i] = new RecordingTask(i, contexts[i], ranOn, homeWhenRun, blocked, gate, completed);
            }
            // fed from the home carrier's own thread, as a waker running there would
            home.enqueue(() -> {
                for (RecordingTask task : tasks) {
                    home.enqueueLocal(task);
                }
            });
            assertTrue(blocked.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "a second carrier must have taken work from the busy one");
            gate.countDown();
            assertTrue(completed.await(WAIT_SECONDS, TimeUnit.SECONDS));

            int movedAway = 0;
            for (int i = 0; i < n; i++) {
                assertEquals(ranOn.get(i), homeWhenRun.get(i),
                        "task " + i + ": its context must name the carrier that ran it");
                assertEquals(ranOn.get(i), contexts[i].carrier().id());
                if (contexts[i].pinned()) {
                    assertEquals(home.id(), ranOn.get(i), "pinned task " + i + " left its carrier");
                } else if (ranOn.get(i) != home.id()) {
                    movedAway++;
                }
            }
            assertTrue(movedAway > 0, "no task was stolen");
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }

    @ParameterizedTest(name = "queue={0}")
    @ValueSource(strings = {"clq", "deque"})
    @DisplayName("place=p2c avoids the carrier with the long queue far more often than a uniform pick")
    void powerOfTwoChoicesAvoidsTheBusyCarrier(String queue) throws Exception {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.of(Map.of(
                "exeris.locality.queue", queue,
                "exeris.locality.place", "p2c")), 4);
        try {
            ExerisCarrierGroup group = scheduler.carrierGroup();
            ExerisCarrierThread busy = group.carrier(0);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            busy.enqueue(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(holding.await(WAIT_SECONDS, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                busy.enqueue(() -> { });
            }
            assertTrue(busy.queueLengthEstimate() >= 500, "estimate " + busy.queueLengthEstimate());

            int samples = 4000;
            int picksOfBusy = 0;
            for (int i = 0; i < samples; i++) {
                if (group.selectShorterOfTwo() == busy) {
                    picksOfBusy++;
                }
            }
            // uniform would pick it a quarter of the time; two choices only when both draws are it
            assertTrue(picksOfBusy < samples * 0.12, "picked the busy carrier " + picksOfBusy + " of " + samples);
            release.countDown();
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }

    @Test
    @DisplayName("steal requires the deque queue")
    void stealRequiresDeque() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.steal", "true")));
        assertTrue(e.getMessage().contains("queue=deque"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LocalityFlags.of(Map.of(
                "exeris.locality.queue", "deque", "exeris.locality.steal", "true",
                "exeris.locality.steal.batch", "0")));
    }
}
