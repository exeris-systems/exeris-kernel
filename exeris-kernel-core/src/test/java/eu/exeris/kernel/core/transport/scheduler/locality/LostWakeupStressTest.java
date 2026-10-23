/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The park/wakeup handshake under every combination of the flags that touch it. A producer that
 * is a foreign platform thread, a carrier's own platform thread, or (in the forked scenario) a
 * mounted virtual thread must always get the idle consumer to run the work: a lost wakeup leaves
 * a latch that never opens, and the test fails by its bounded wait.
 */
@DisplayName("Carrier wakeup handshake")
class LostWakeupStressTest {

    private static final int ROUNDS = 20_000;
    private static final long WAIT_SECONDS = 60;

    static Stream<Map<String, String>> flagMatrix() {
        List<Map<String, String>> cells = new ArrayList<>();
        for (String spin : new String[] {"0", "20000"}) {
            for (String wakeup : new String[] {"getAndSet", "readFirst"}) {
                Map<String, String> cell = new LinkedHashMap<>();
                cell.put("exeris.locality.idle.spinNs", spin);
                cell.put("exeris.locality.wakeup", wakeup);
                cells.add(cell);
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flagMatrix")
    @DisplayName("foreign platform producers: every task enqueued to an idle carrier runs")
    void foreignProducers(Map<String, String> flags) throws Exception {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.of(flags), 2);
        try {
            ExerisCarrierGroup group = scheduler.carrierGroup();
            int producers = 3;
            AtomicInteger ran = new AtomicInteger();
            CountDownLatch finished = new CountDownLatch(producers);
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                final int seed = p;
                Thread thread = new Thread(() -> {
                    for (int r = 0; r < ROUNDS; r++) {
                        CountDownLatch one = new CountDownLatch(1);
                        group.carrier(seed + r).enqueue(() -> {
                            ran.incrementAndGet();
                            one.countDown();
                        });
                        try {
                            if (!one.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                                return;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    finished.countDown();
                });
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            assertTrue(finished.await(2 * WAIT_SECONDS, TimeUnit.SECONDS),
                    "a wakeup was lost: ran=" + ran.get() + " of " + producers * ROUNDS);
            assertEquals(producers * ROUNDS, ran.get());
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flagMatrix")
    @DisplayName("carrier producers: a task handed around a ring of carriers is never stranded")
    void carrierRing(Map<String, String> flags) throws Exception {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.of(flags), 4);
        try {
            ExerisCarrierGroup group = scheduler.carrierGroup();
            CountDownLatch finished = new CountDownLatch(1);
            AtomicInteger hops = new AtomicInteger();
            int total = ROUNDS * 2;
            Runnable[] hop = new Runnable[1];
            hop[0] = () -> {
                int n = hops.incrementAndGet();
                if (n >= total) {
                    finished.countDown();
                } else {
                    group.carrier(n).enqueue(hop[0]);
                }
            };
            group.carrier(0).enqueue(hop[0]);
            assertTrue(finished.await(WAIT_SECONDS, TimeUnit.SECONDS), "ring stalled at hop " + hops.get());
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flagMatrix")
    @DisplayName("mounted virtual-thread and foreign-thread unparks under the installed scheduler")
    void installedScheduler(Map<String, String> flags) throws Exception {
        Map<String, String> result = ForkedSchedulerRunner.run("stress", flags);
        assertEquals(8 * 3000, ForkedSchedulerRunner.intOf(result, "ring"));
        assertEquals(16 * 2000, ForkedSchedulerRunner.intOf(result, "acks"));
    }
}
