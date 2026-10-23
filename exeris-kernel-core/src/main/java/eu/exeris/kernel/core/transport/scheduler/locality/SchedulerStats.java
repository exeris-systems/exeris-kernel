/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts of the scheduling events that any thread can cause: where each virtual thread was
 * placed at start, how each wakeup was routed, and how many unparks were issued. Per-carrier
 * events live in {@link CarrierCounters}. An instance exists only while
 * {@code exeris.locality.stats} is on.
 */
final class SchedulerStats {

    final LongAdder startFactory = new LongAdder();
    final LongAdder startPollerPreferred = new LongAdder();
    final LongAdder startPollerName = new LongAdder();
    final LongAdder startCurrentMounted = new LongAdder();
    final LongAdder startCurrentScope = new LongAdder();
    final LongAdder startP2c = new LongAdder();
    final LongAdder startP2cOther = new LongAdder();
    final LongAdder startRandom = new LongAdder();
    final LongAdder startBuiltin = new LongAdder();
    final LongAdder continueLocal = new LongAdder();
    final LongAdder continueInboxCarrier = new LongAdder();
    final LongAdder continueInboxRemote = new LongAdder();
    final LongAdder continueBuiltin = new LongAdder();
    final LongAdder idleUnparks = new LongAdder();
    final LongAdder idleUnparksElided = new LongAdder();

    void reset() {
        for (LongAdder adder : new LongAdder[] {startFactory, startPollerPreferred, startPollerName,
                startCurrentMounted, startCurrentScope, startP2c, startP2cOther, startRandom, startBuiltin,
                continueLocal, continueInboxCarrier, continueInboxRemote, continueBuiltin, idleUnparks,
                idleUnparksElided}) {
            adder.reset();
        }
    }

    /** An immutable view, sorted by key, of every counter. */
    Map<String, Long> snapshot(ExerisCarrierGroup group, ExerisCarrierScheduler.TimerStats timers) {
        Map<String, Long> out = new TreeMap<>();
        out.put("start.factory", startFactory.sum());
        out.put("start.poller.preferred", startPollerPreferred.sum());
        out.put("start.poller.name", startPollerName.sum());
        out.put("start.current.mounted", startCurrentMounted.sum());
        out.put("start.current.scope", startCurrentScope.sum());
        out.put("start.p2c", startP2c.sum());
        out.put("start.p2c.other", startP2cOther.sum());
        out.put("start.random", startRandom.sum());
        out.put("start.builtin", startBuiltin.sum());
        out.put("continue.local", continueLocal.sum());
        out.put("continue.inbox.carrier", continueInboxCarrier.sum());
        out.put("continue.inbox.remote", continueInboxRemote.sum());
        out.put("continue.builtin", continueBuiltin.sum());
        out.put("idle.unparks", idleUnparks.sum());
        out.put("idle.unparks.elided", idleUnparksElided.sum());
        out.put("timer.scheduled", timers.scheduled());
        out.put("timer.cancelled", timers.cancelled());
        long[] total = new long[CarrierCounters.WAKE_PEER + 1];
        for (int i = 0; i < group.size(); i++) {
            CarrierCounters counters = group.carrier(i).counters();
            if (counters == null) {
                continue;
            }
            for (int slot = 0; slot < total.length; slot++) {
                total[slot] += counters.get(slot);
            }
            String prefix = "carrier." + i + ".";
            out.put(prefix + "tasks.run", counters.get(CarrierCounters.TASKS_RUN));
            out.put(prefix + "idle.parks", counters.get(CarrierCounters.IDLE_PARKS));
            out.put(prefix + "steal.tasks", counters.get(CarrierCounters.STEAL_TASKS));
        }
        out.put("lifo.hits", total[CarrierCounters.LIFO_HITS]);
        out.put("idle.parks", total[CarrierCounters.IDLE_PARKS]);
        out.put("idle.spins", total[CarrierCounters.IDLE_SPINS]);
        out.put("idle.spins.found", total[CarrierCounters.SPINS_FOUND]);
        out.put("yield.budget", total[CarrierCounters.YIELD_BUDGET]);
        out.put("tasks.run", total[CarrierCounters.TASKS_RUN]);
        out.put("steal.attempts", total[CarrierCounters.STEAL_ATTEMPTS]);
        out.put("steal.successes", total[CarrierCounters.STEAL_SUCCESSES]);
        out.put("steal.tasks", total[CarrierCounters.STEAL_TASKS]);
        out.put("wake.peer", total[CarrierCounters.WAKE_PEER]);
        return Collections.unmodifiableMap(out);
    }
}
