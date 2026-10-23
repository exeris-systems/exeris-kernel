/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specifies shutdown with work still queued: the running task completes, the carrier exits without
 * starting another, and what was queued is abandoned (see {@link ExerisCarrierThread#shutdown()}).
 */
@DisplayName("Carrier shutdown with a non-empty queue")
class CarrierShutdownTest {

    @ParameterizedTest(name = "queue={0}")
    @ValueSource(strings = {"clq", "deque"})
    @DisplayName("the running task completes, queued tasks are abandoned, the carrier thread exits")
    void queuedTasksAreAbandoned(String queue) throws Exception {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.of(Map.of(
                "exeris.locality.queue", queue,
                "exeris.locality.yield.us", "1")), 1);
        ExerisCarrierThread carrier = scheduler.carrierGroup().carrier(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger afterwards = new AtomicInteger();
        carrier.enqueue(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // past the 1 microsecond yield budget, so the batch ends after this task
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < 2_000_000L) {
                Thread.onSpinWait();
            }
            completed.countDown();
        });
        assertTrue(started.await(30, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) {
            carrier.enqueue(afterwards::incrementAndGet);
        }
        carrier.shutdown();
        release.countDown();

        assertTrue(completed.await(30, TimeUnit.SECONDS), "the running task must complete");
        carrier.carrierThread().join(30_000);
        assertFalse(carrier.carrierThread().isAlive(), "the carrier thread must exit");
        assertEquals(0, afterwards.get(), "tasks queued behind the running one are abandoned");

        CountDownLatch late = new CountDownLatch(1);
        carrier.enqueue(late::countDown);
        assertFalse(late.await(200, TimeUnit.MILLISECONDS), "a task enqueued after shutdown is never run");
    }
}
