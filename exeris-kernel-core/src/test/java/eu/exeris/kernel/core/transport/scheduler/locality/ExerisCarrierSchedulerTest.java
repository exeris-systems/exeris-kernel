/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
}
