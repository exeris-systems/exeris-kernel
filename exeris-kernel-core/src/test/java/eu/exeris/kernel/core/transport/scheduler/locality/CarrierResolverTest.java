/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Carrier resolution and scheduling context")
class CarrierResolverTest {

    @Test
    @DisplayName("a carrier's platform thread resolves to that carrier; a foreign platform thread to null")
    void classifiesPlatformThreads() throws InterruptedException {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.defaults(), 2);
        try {
            ExerisCarrierThread carrier = scheduler.carrierGroup().carrier(1);
            AtomicReference<ExerisCarrierThread> resolved = new AtomicReference<>();
            AtomicReference<ExerisCarrierThread> viaScope = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            carrier.enqueue(() -> {
                resolved.set(CarrierResolver.mounted());
                viaScope.set(ExerisCarrierThread.currentCarrier());
                ran.countDown();
            });
            assertTrue(ran.await(10, TimeUnit.SECONDS));
            assertSame(carrier, resolved.get());
            assertSame(carrier, viaScope.get());
            assertNull(CarrierResolver.mounted(), "the test thread is a foreign platform thread");
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }

    @Test
    @DisplayName("a context's carrier can be re-homed; a pinned context refuses")
    void contextRehoming() {
        ExerisCarrierScheduler scheduler = new ExerisCarrierScheduler(null, LocalityFlags.defaults(), 2);
        try {
            ExerisCarrierThread a = scheduler.carrierGroup().carrier(0);
            ExerisCarrierThread b = scheduler.carrierGroup().carrier(1);
            CarrierSchedulingContext movable = new CarrierSchedulingContext(a.id(), a);
            assertFalse(movable.pinned());
            movable.moveTo(b);
            assertSame(b, movable.carrier());
            assertEquals(b.id(), movable.carrierId());
            CarrierSchedulingContext pinned = new CarrierSchedulingContext(a.id(), a, true);
            assertTrue(pinned.pinned());
            assertThrows(IllegalStateException.class, () -> pinned.moveTo(b));
            assertSame(a, pinned.carrier());
        } finally {
            scheduler.carrierGroup().shutdown();
        }
    }
}
