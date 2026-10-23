/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The tunables of the locality scheduler, read once when the scheduler is constructed.
 *
 * <p>Every flag's default reproduces the baseline scheduler, so a flag that is not set changes
 * nothing. Values are fixed for the life of a scheduler: the scheduler and its carriers copy them
 * into final fields and never consult a system property again on a scheduling path.
 *
 * <p>A value that does not parse, or a combination that cannot work, is rejected at construction
 * with a message naming the property; a scheduler is never started in a configuration it would
 * silently ignore.
 */
final class LocalityFlags {

    static final String PREFIX = "exeris.locality.";

    /** How the caller of {@code onStart}/{@code onContinue} is resolved to a carrier. */
    enum CarrierResolve { SCOPE, MOUNTED }

    /** How a producer decides whether the consumer carrier needs an unpark. */
    enum Wakeup { GETANDSET, READFIRST }

    /** Where a virtual thread that has no carrier yet is first placed. */
    enum Place { CURRENT, P2C }

    /** The data structure behind a carrier's run queue. */
    enum QueueKind { CLQ, DEQUE }

    /** How a read-poller virtual thread is routed to its carrier. */
    enum PollerRoute { NAME, PREFERRED }

    private final boolean allVthreads;
    private final boolean perCarrierPollers;
    private final long yieldBudgetNs;
    private final CarrierResolve carrierResolve;
    private final PollerRoute pollerRoute;
    private final boolean factoryPreferredCarrier;
    private final boolean timerCount;
    private final long idleSpinNs;
    private final Wakeup wakeup;
    private final QueueKind queue;
    private final boolean selfWakeup;
    private final int yieldCheckEvery;
    private final boolean steal;
    private final int stealBatch;
    private final Place place;

    private LocalityFlags(Function<String, String> source) {
        this.allVthreads = bool(source, "allVthreads");
        this.perCarrierPollers = intValue(source, "jdk.pollerMode", -1) == 3;
        this.yieldBudgetNs = java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(
                longValue(source, PREFIX + "yield.us", 50));
        this.carrierResolve = choice(source, "carrierResolve", CarrierResolve.SCOPE);
        this.pollerRoute = choice(source, "poller.route", PollerRoute.NAME);
        this.factoryPreferredCarrier = bool(source, "factory.preferredCarrier");
        this.timerCount = bool(source, "timer.count");
        this.idleSpinNs = longValue(source, PREFIX + "idle.spinNs", 0);
        if (idleSpinNs < 0) {
            throw new IllegalArgumentException(PREFIX + "idle.spinNs must not be negative, was " + idleSpinNs);
        }
        this.wakeup = choice(source, "wakeup", Wakeup.GETANDSET);
        this.queue = choice(source, "queue", QueueKind.CLQ);
        this.selfWakeup = bool(source, "selfWakeup");
        this.yieldCheckEvery = intValue(source, PREFIX + "yield.checkEvery", 1);
        if (yieldCheckEvery < 1) {
            throw new IllegalArgumentException(
                    PREFIX + "yield.checkEvery must be at least 1, was " + yieldCheckEvery);
        }
        this.steal = bool(source, "steal");
        this.stealBatch = stealBatch(source.apply(PREFIX + "steal.batch"));
        this.place = choice(source, "place", Place.CURRENT);
        validate();
    }

    /** Flags as set on the JVM's system properties. */
    static LocalityFlags fromSystem() {
        return new LocalityFlags(System::getProperty);
    }

    /** Flags from an explicit property map; keys are the full property names. */
    static LocalityFlags of(Map<String, String> properties) {
        return new LocalityFlags(properties::get);
    }

    /** All flags at their defaults. */
    static LocalityFlags defaults() {
        return of(Map.of());
    }

    boolean allVthreads() {
        return allVthreads;
    }

    boolean perCarrierPollers() {
        return perCarrierPollers;
    }

    long yieldBudgetNs() {
        return yieldBudgetNs;
    }

    CarrierResolve carrierResolve() {
        return carrierResolve;
    }

    PollerRoute pollerRoute() {
        return pollerRoute;
    }

    boolean factoryPreferredCarrier() {
        return factoryPreferredCarrier;
    }

    boolean timerCount() {
        return timerCount;
    }

    private static int stealBatch(String value) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("half")) {
            return 0;
        }
        try {
            int n = Integer.parseInt(value.trim());
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // reported below with the other invalid values
        }
        throw new IllegalArgumentException(PREFIX + "steal.batch must be half or an integer of at least 1, was '"
                + value + "'");
    }

    private void validate() {
        if (steal && queue != QueueKind.DEQUE) {
            throw new IllegalArgumentException(
                    PREFIX + "steal=true requires " + PREFIX + "queue=deque: only the deque can be stolen from");
        }
        if (selfWakeup && queue != QueueKind.DEQUE) {
            throw new IllegalArgumentException(
                    PREFIX + "selfWakeup=true requires " + PREFIX + "queue=deque: only the deque has an owner side");
        }
        if (selfWakeup && carrierResolve != CarrierResolve.MOUNTED) {
            throw new IllegalArgumentException(PREFIX + "selfWakeup=true requires " + PREFIX
                    + "carrierResolve=mounted: a stale scope binding would push to a deque "
                    + "from a thread that does not own it");
        }
    }

    boolean steal() {
        return steal;
    }

    /** Tasks taken per steal; 0 means half of the victim's queue. */
    int stealBatch() {
        return stealBatch;
    }

    Place place() {
        return place;
    }

    QueueKind queue() {
        return queue;
    }

    boolean selfWakeup() {
        return selfWakeup;
    }

    int yieldCheckEvery() {
        return yieldCheckEvery;
    }

    long idleSpinNs() {
        return idleSpinNs;
    }

    Wakeup wakeup() {
        return wakeup;
    }

    static boolean bool(Function<String, String> source, String name) {
        String value = source.apply(PREFIX + name);
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("true") && !v.equals("false")) {
            throw new IllegalArgumentException(PREFIX + name + " must be true or false, was '" + value + "'");
        }
        return v.equals("true");
    }

    static int intValue(Function<String, String> source, String property, int fallback) {
        String value = source.apply(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be an integer, was '" + value + "'", e);
        }
    }

    static long longValue(Function<String, String> source, String property, long fallback) {
        String value = source.apply(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be an integer, was '" + value + "'", e);
        }
    }

    static <E extends Enum<E>> E choice(Function<String, String> source, String name, E fallback) {
        String value = source.apply(PREFIX + name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        for (E candidate : fallback.getDeclaringClass().getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (E candidate : fallback.getDeclaringClass().getEnumConstants()) {
            allowed.append(allowed.isEmpty() ? "" : "|").append(candidate.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException(PREFIX + name + " must be one of " + allowed + ", was '" + value + "'");
    }
}
