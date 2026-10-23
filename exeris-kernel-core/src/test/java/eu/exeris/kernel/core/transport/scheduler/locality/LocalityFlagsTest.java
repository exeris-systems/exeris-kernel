/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.CarrierResolve;
import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.PollerRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LocalityFlags")
class LocalityFlagsTest {

    @Test
    @DisplayName("every default reproduces the baseline scheduler")
    void defaultsAreBaseline() {
        LocalityFlags flags = LocalityFlags.defaults();
        assertEquals(CarrierResolve.SCOPE, flags.carrierResolve());
        assertEquals(PollerRoute.NAME, flags.pollerRoute());
        assertFalse(flags.factoryPreferredCarrier());
        assertFalse(flags.timerCount());
        assertFalse(flags.allVthreads());
        assertFalse(flags.perCarrierPollers());
        assertEquals(50_000L, flags.yieldBudgetNs());
    }

    @Test
    @DisplayName("values are parsed case-insensitively")
    void parsesValues() {
        LocalityFlags flags = LocalityFlags.of(Map.of(
                "exeris.locality.carrierResolve", "Mounted",
                "exeris.locality.poller.route", "preferred",
                "exeris.locality.factory.preferredCarrier", "true",
                "exeris.locality.timer.count", "TRUE",
                "jdk.pollerMode", "3"));
        assertEquals(CarrierResolve.MOUNTED, flags.carrierResolve());
        assertEquals(PollerRoute.PREFERRED, flags.pollerRoute());
        assertTrue(flags.factoryPreferredCarrier());
        assertTrue(flags.timerCount());
        assertTrue(flags.perCarrierPollers());
    }

    @Test
    @DisplayName("an unparsable value is rejected with the property name")
    void rejectsInvalidValues() {
        IllegalArgumentException choice = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.carrierResolve", "bogus")));
        assertTrue(choice.getMessage().contains("exeris.locality.carrierResolve"));
        assertTrue(choice.getMessage().contains("scope|mounted"));
        IllegalArgumentException bool = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.timer.count", "yes")));
        assertTrue(bool.getMessage().contains("exeris.locality.timer.count"));
    }

    @Test
    @DisplayName("combinations that cannot work fail at construction with a message naming the flags")
    void rejectsInvalidCombinations() {
        IllegalArgumentException needsDeque = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.selfWakeup", "true",
                        "exeris.locality.carrierResolve", "mounted")));
        assertTrue(needsDeque.getMessage().contains("queue=deque"), needsDeque.getMessage());
        IllegalArgumentException needsMounted = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.selfWakeup", "true",
                        "exeris.locality.queue", "deque")));
        assertTrue(needsMounted.getMessage().contains("carrierResolve=mounted"), needsMounted.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.yield.checkEvery", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.idle.spinNs", "-1")));
        assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.lifoSlot", "true")));
        assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.poller.priority", "front")));
        LocalityFlags valid = LocalityFlags.of(Map.of("exeris.locality.selfWakeup", "true",
                "exeris.locality.queue", "deque", "exeris.locality.carrierResolve", "mounted"));
        assertTrue(valid.selfWakeup());
        assertEquals(LocalityFlags.QueueKind.DEQUE, valid.queue());
    }

    @Test
    @DisplayName("describe lists every flag at its default, sorted by name")
    void describeDefaults() {
        assertEquals("exeris.locality.allVthreads=false exeris.locality.carrierResolve=scope "
                + "exeris.locality.factory.preferredCarrier=false exeris.locality.idle.spinNs=0 "
                + "exeris.locality.lifoSlot=false exeris.locality.place=current "
                + "exeris.locality.poller.priority=fifo exeris.locality.poller.route=name "
                + "exeris.locality.queue=clq exeris.locality.selfWakeup=false exeris.locality.stats=false "
                + "exeris.locality.steal=false exeris.locality.steal.batch=half "
                + "exeris.locality.timer.count=false exeris.locality.wakeup=getandset "
                + "exeris.locality.yield.checkEvery=1 exeris.locality.yield.us=50 jdk.pollerMode=-1",
                LocalityFlags.defaults().describe());
    }

    @Test
    @DisplayName("describe reports every flag as set, in the canonical spelling")
    void describeAllSet() {
        LocalityFlags flags = LocalityFlags.of(Map.ofEntries(
                Map.entry("exeris.locality.allVthreads", "TRUE"),
                Map.entry("exeris.locality.carrierResolve", "Mounted"),
                Map.entry("exeris.locality.factory.preferredCarrier", "true"),
                Map.entry("exeris.locality.idle.spinNs", "20000"),
                Map.entry("exeris.locality.lifoSlot", "true"),
                Map.entry("exeris.locality.place", "P2C"),
                Map.entry("exeris.locality.poller.priority", "Front"),
                Map.entry("exeris.locality.poller.route", "PREFERRED"),
                Map.entry("exeris.locality.queue", "Deque"),
                Map.entry("exeris.locality.selfWakeup", "true"),
                Map.entry("exeris.locality.stats", "true"),
                Map.entry("exeris.locality.steal", "true"),
                Map.entry("exeris.locality.steal.batch", "8"),
                Map.entry("exeris.locality.timer.count", "true"),
                Map.entry("exeris.locality.wakeup", "readFirst"),
                Map.entry("exeris.locality.yield.checkEvery", "8"),
                Map.entry("exeris.locality.yield.us", "200"),
                Map.entry("jdk.pollerMode", "3")));
        assertEquals("exeris.locality.allVthreads=true exeris.locality.carrierResolve=mounted "
                + "exeris.locality.factory.preferredCarrier=true exeris.locality.idle.spinNs=20000 "
                + "exeris.locality.lifoSlot=true exeris.locality.place=p2c "
                + "exeris.locality.poller.priority=front exeris.locality.poller.route=preferred "
                + "exeris.locality.queue=deque exeris.locality.selfWakeup=true exeris.locality.stats=true "
                + "exeris.locality.steal=true exeris.locality.steal.batch=8 "
                + "exeris.locality.timer.count=true exeris.locality.wakeup=readfirst "
                + "exeris.locality.yield.checkEvery=8 exeris.locality.yield.us=200 jdk.pollerMode=3",
                flags.describe());
        assertTrue(flags.stats());
    }
}
