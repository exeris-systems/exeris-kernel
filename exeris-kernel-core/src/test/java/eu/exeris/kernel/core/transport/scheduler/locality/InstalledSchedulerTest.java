/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static eu.exeris.kernel.core.transport.scheduler.locality.ForkedSchedulerRunner.intOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios that run virtual threads under the installed locality scheduler, in a forked Loom JDK.
 * Every one asserts that carriers of this scheduler actually ran the work, so a JVM that silently
 * fell back to the built-in scheduler cannot pass.
 */
@DisplayName("Locality scheduler installed in a JVM")
class InstalledSchedulerTest {

    @Test
    @DisplayName("defaults: streams run, park and unpark on their carrier; children go to the built-in scheduler")
    void defaultsSmoke() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("smoke", Map.of());
        int streams = intOf(r, "streams");
        assertEquals(streams, intOf(r, "onCustom"), "every stream must have run on a locality carrier");
        assertEquals(streams, intOf(r, "stable"), "a stream must stay on its carrier across park/unpark");
        assertEquals(0, intOf(r, "childOnCustom"), "unattached children use the built-in scheduler by default");
        assertEquals(0, intOf(r, "timersScheduled"), "timers are not counted by default");
    }

    @Test
    @DisplayName("timer.count counts the timed parks and the cancellations")
    void timerCount() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("smoke", Map.of(
                "exeris.locality.timer.count", "true",
                "exeris.locality.carrierResolve", "mounted"));
        assertEquals(intOf(r, "streams"), intOf(r, "onCustom"));
        assertTrue(intOf(r, "timersScheduled") > 0, "timed parks must reach schedule()");
        assertTrue(intOf(r, "timersCancelled") <= intOf(r, "timersScheduled"));
    }

    @Test
    @DisplayName("allVthreads places children on locality carriers, resolving the starter's carrier when mounted")
    void allVthreadsMounted() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("smoke", Map.of(
                "exeris.locality.allVthreads", "true",
                "exeris.locality.carrierResolve", "mounted",
                "exeris.locality.factory.preferredCarrier", "true"));
        int streams = intOf(r, "streams");
        assertEquals(streams, intOf(r, "onCustom"));
        assertEquals(streams, intOf(r, "stable"));
        assertEquals(streams, intOf(r, "childOnCustom"));
    }

    @Test
    @DisplayName("resolver classification: factory vthread, plain vthread, carrier platform thread, foreign thread")
    void resolverClassification() throws Exception {
        Map<String, String> mounted = ForkedSchedulerRunner.run("resolve", Map.of());
        assertEquals("true", mounted.get("jlaAvailable"), "the JDK internals must be reachable in the fork");
        int factoryMounted = intOf(mounted, "factoryMounted");
        assertTrue(factoryMounted >= 0, "a factory vthread is mounted on a locality carrier");
        assertEquals(factoryMounted, intOf(mounted, "factoryScope"));
        assertEquals(-1, intOf(mounted, "plainVirtualMounted"), "a built-in vthread is remote");
        assertEquals(1, intOf(mounted, "carrierPlatform"), "a task on carrier 1 resolves to carrier 1");
        assertEquals(-1, intOf(mounted, "foreignPlatform"), "a foreign platform thread is remote");
    }

    @Test
    @DisplayName("poller.route=name leaves a preferred-carrier thread to the built-in scheduler")
    void pollerRouteName() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("poller", Map.of());
        assertEquals(-1, intOf(r, "ranOn"));
        assertEquals("false", r.get("pinned"));
    }

    @Test
    @DisplayName("poller.route=preferred runs a preferred-carrier thread on that carrier, pinned, "
            + "although its starter is not a factory thread")
    void pollerRoutePreferred() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("poller", Map.of(
                "exeris.locality.poller.route", "preferred"));
        assertEquals(intOf(r, "target"), intOf(r, "ranOn"));
        assertEquals("true", r.get("pinned"));
    }

    @Test
    @DisplayName("the deque classes are loaded only when queue=deque")
    void dequeClassesLoadOnlyWhenSelected() throws Exception {
        List<String> logging = List.of("-Xlog:class+load:stdout");
        String defaults = ForkedSchedulerRunner.runForOutput("smoke", Map.of(), logging);
        for (String name : List.of("DequeCarrierQueue", "WorkStealingDeque", "MpscInbox")) {
            assertFalse(defaults.contains(name), name + " must not be loaded at the defaults");
        }
        assertTrue(defaults.contains("ClqCarrierQueue"));
        String deque = ForkedSchedulerRunner.runForOutput("smoke", Map.of("exeris.locality.queue", "deque"), logging);
        for (String name : List.of("DequeCarrierQueue", "WorkStealingDeque", "MpscInbox")) {
            assertTrue(deque.contains(name), name + " must be loaded for queue=deque");
        }
        assertFalse(deque.contains("ClqCarrierQueue"), "the baseline queue must not be loaded for queue=deque");
    }

    @Test
    @DisplayName("queue=deque with selfWakeup: streams run, park and unpark on their carrier")
    void dequeSelfWakeupSmoke() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("smoke", Map.of(
                "exeris.locality.queue", "deque",
                "exeris.locality.selfWakeup", "true",
                "exeris.locality.carrierResolve", "mounted",
                "exeris.locality.allVthreads", "true"));
        int streams = intOf(r, "streams");
        assertEquals(streams, intOf(r, "onCustom"));
        assertEquals(streams, intOf(r, "stable"));
        assertEquals(streams, intOf(r, "childOnCustom"));
    }

    /** Every flag on at once: the v2 scheduler as a whole. */
    private static final Map<String, String> ALL_ON = Map.ofEntries(
            Map.entry("exeris.locality.allVthreads", "true"),
            Map.entry("exeris.locality.carrierResolve", "mounted"),
            Map.entry("exeris.locality.poller.route", "preferred"),
            Map.entry("exeris.locality.factory.preferredCarrier", "true"),
            Map.entry("exeris.locality.timer.count", "true"),
            Map.entry("exeris.locality.idle.spinNs", "20000"),
            Map.entry("exeris.locality.wakeup", "readFirst"),
            Map.entry("exeris.locality.queue", "deque"),
            Map.entry("exeris.locality.selfWakeup", "true"),
            Map.entry("exeris.locality.yield.checkEvery", "8"),
            Map.entry("exeris.locality.steal", "true"),
            Map.entry("exeris.locality.steal.batch", "half"),
            Map.entry("exeris.locality.place", "p2c"));

    @Test
    @DisplayName("all flags on: streams and children run on locality carriers and complete")
    void allOnSmoke() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("smoke", ALL_ON);
        int streams = intOf(r, "streams");
        assertEquals(streams, intOf(r, "onCustom"), "every stream must have run on a locality carrier");
        assertEquals(streams, intOf(r, "childOnCustom"), "p2c places the children on locality carriers");
        assertTrue(intOf(r, "timersScheduled") > 0);
    }

    @Test
    @DisplayName("all flags on: token ring and foreign wakeups complete")
    void allOnStress() throws Exception {
        Map<String, String> r = ForkedSchedulerRunner.run("stress", ALL_ON);
        assertEquals(8 * 3000, intOf(r, "ring"));
        assertEquals(16 * 2000, intOf(r, "acks"));
    }

    @Test
    @DisplayName("all flags on: resolution and poller routing are unchanged by stealing")
    void allOnResolveAndPoller() throws Exception {
        Map<String, String> resolve = ForkedSchedulerRunner.run("resolve", ALL_ON);
        assertTrue(intOf(resolve, "factoryMounted") >= 0);
        assertEquals(-1, intOf(resolve, "foreignPlatform"));
        assertEquals(1, intOf(resolve, "carrierPlatform"));
        Map<String, String> poller = ForkedSchedulerRunner.run("poller", ALL_ON);
        assertEquals(intOf(poller, "target"), intOf(poller, "ranOn"));
        assertEquals("true", poller.get("pinned"));
    }
}
