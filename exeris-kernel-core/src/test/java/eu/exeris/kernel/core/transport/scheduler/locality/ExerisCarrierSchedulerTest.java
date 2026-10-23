/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExerisCarrierScheduler and Locality Subsystem Unit Tests")
class ExerisCarrierSchedulerTest {

    @Test
    @DisplayName("CarrierSchedulingContext holds carrier reference and vThreadId")
    void testCarrierSchedulingContext() {
        CarrierSchedulingContext ctx = new CarrierSchedulingContext(3, null);
        assertEquals(3, ctx.carrierId());
        assertEquals(-1, ctx.vThreadId());
        ctx.setVThreadId(42);
        assertEquals(42, ctx.vThreadId());
    }

    @Test
    @DisplayName("ExerisCarrierGroup initializes carriers and handles index indexing")
    void testCarrierGroup() {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null);
        ExerisCarrierGroup group = scheduler.carrierGroup();
        assertNotNull(group);
        assertTrue(group.size() > 0);

        ExerisCarrierThread carrier0 = group.carrier(0);
        assertNotNull(carrier0);
        assertEquals(0, carrier0.id());

        ExerisCarrierThread carrierMod = group.carrier(group.size());
        assertEquals(carrier0.id(), carrierMod.id());

        ExerisCarrierThread randomCarrier = group.selectCarrier();
        assertNotNull(randomCarrier);

        group.shutdown();
    }

    @Test
    @DisplayName("bindToCore with negative core returns false cleanly")
    void testBindToCoreNegative() {
        assertFalse(ExerisCarrierThread.bindToCore(-1));
    }

    @Test
    @DisplayName("RoundRobinCarrierExecutionBackend starts task and distributes execution")
    void testRoundRobinExecutionBackend() throws InterruptedException {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null);
        ExerisCarrierGroup group = scheduler.carrierGroup();
        RoundRobinCarrierExecutionBackend backend = new RoundRobinCarrierExecutionBackend(group);
        assertEquals(group, backend.group());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean(false);

        backend.start("test-stream-1", () -> {
            executed.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get());

        group.shutdown();
    }

    @Test
    @DisplayName("stats off: no counters exist, the snapshot is empty and reset is a no-op")
    void statsOffHoldsNoCounters() {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null);
        ExerisCarrierGroup group = scheduler.carrierGroup();
        assertNull(scheduler.stats());
        for (int i = 0; i < group.size(); i++) {
            assertNull(group.carrier(i).counters());
        }
        assertTrue(ExerisCarrierScheduler.statsSnapshot().isEmpty());
        ExerisCarrierScheduler.resetStats();
        group.shutdown();
    }

    @Test
    @DisplayName("stats on: the snapshot carries every key, sorted, and is immutable")
    void statsOnSnapshotShape() {
        LocalityFlags flags = LocalityFlags.of(java.util.Map.of("exeris.locality.stats", "true"));
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, flags, 2);
        java.util.Map<String, Long> snapshot = ExerisCarrierScheduler.statsSnapshot();
        assertTrue(snapshot.keySet().containsAll(java.util.List.of("start.factory", "start.p2c.other",
                "continue.inbox.remote", "lifo.hits", "idle.parks", "idle.unparks.elided", "yield.budget",
                "tasks.run", "steal.tasks", "wake.peer", "timer.scheduled", "timer.cancelled",
                "carrier.0.tasks.run", "carrier.1.idle.parks", "carrier.1.steal.tasks")));
        assertEquals(new java.util.TreeSet<>(snapshot.keySet()).toString(), snapshot.keySet().toString());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", 1L));
        scheduler.carrierGroup().shutdown();
    }
}
