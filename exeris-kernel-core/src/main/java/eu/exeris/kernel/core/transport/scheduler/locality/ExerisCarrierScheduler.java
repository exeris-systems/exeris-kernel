/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.Thread.VirtualThreadScheduler;
import java.lang.Thread.VirtualThreadTask;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import eu.exeris.kernel.core.transport.scheduler.locality.ExerisCarrierThread.CarrierWorkerThread;
import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.CarrierResolve;
import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.PollerRoute;

/**
 * Custom VirtualThreadScheduler loaded by the JDK via {@code -Djdk.virtualThreadScheduler.implClass=}
 * {@code eu.exeris.kernel.core.transport.scheduler.locality.ExerisCarrierScheduler}.
 *
 * <p>Routes virtual thread tasks to their designated carrier thread when one is assigned
 * (via {@link CarrierSchedulingContext} attachment), or when {@code -Djdk.pollerMode=3}
 * (per-carrier pollers) is enabled for the read-poller thread. Otherwise falls back to
 * the JDK's built-in scheduler.
 */
public class ExerisCarrierScheduler implements VirtualThreadScheduler {

    private static final System.Logger LOG = System.getLogger(ExerisCarrierScheduler.class.getName());
    private static volatile ExerisCarrierScheduler instance;
    private static final LongAdder TIMERS_SCHEDULED = new LongAdder();
    private static final LongAdder TIMERS_CANCELLED = new LongAdder();

    private final VirtualThreadScheduler builtinScheduler;
    private final ExerisCarrierGroup group;
    private final boolean perCarrierPollers;
    private final boolean assignAllVThreads;
    private final boolean resolveMounted;
    private final boolean pollerRoutePreferred;
    private final boolean timerCount;
    private final boolean selfWakeup;
    private final boolean placeP2c;
    private final boolean stats;
    private final SchedulerStats schedulerStats;

    /**
     * Counts of timed tasks the JDK handed to {@link #schedule} and of those cancelled before they
     * fired; every timed park schedules one and most are cancelled by the wakeup that beats them.
     * Only counted while {@code exeris.locality.timer.count=true}.
     */
    public record TimerStats(long scheduled, long cancelled) {
    }

    public ExerisCarrierScheduler(VirtualThreadScheduler builtinScheduler) {
        this(builtinScheduler, LocalityFlags.fromSystem(), 0);
    }

    ExerisCarrierScheduler(VirtualThreadScheduler builtinScheduler, LocalityFlags flags, int carrierCount) {
        this.builtinScheduler = builtinScheduler;
        this.perCarrierPollers = flags.perCarrierPollers();
        this.assignAllVThreads = flags.allVthreads();
        this.resolveMounted = flags.carrierResolve() == CarrierResolve.MOUNTED;
        this.pollerRoutePreferred = flags.pollerRoute() == PollerRoute.PREFERRED;
        this.timerCount = flags.timerCount() || flags.stats();
        this.selfWakeup = flags.selfWakeup();
        this.placeP2c = flags.place() == LocalityFlags.Place.P2C;
        this.stats = flags.stats();
        this.schedulerStats = stats ? new SchedulerStats() : null;
        LOG.log(System.Logger.Level.INFO, "[ExerisCarrierScheduler] locality flags: {0}", flags.describe());
        this.group = new ExerisCarrierGroup(this, flags, carrierCount);
        instance = this;
    }

    /** The shared counters; {@code null} unless stats are on. */
    SchedulerStats stats() {
        return schedulerStats;
    }

    /**
     * An immutable snapshot of the scheduling event counts, sorted by key. Empty when
     * {@code exeris.locality.stats} is off or the scheduler is not installed; it never installs
     * the scheduler. Values are monotonic but are read while the carriers run, so they are not a
     * consistent cut.
     */
    public static Map<String, Long> statsSnapshot() {
        ExerisCarrierScheduler inst = instance;
        if (inst == null || !inst.stats) {
            return Collections.emptyMap();
        }
        return inst.schedulerStats.snapshot(inst.group, timerStats());
    }

    /** Zeroes every counter that {@link #statsSnapshot()} reports; a no-op when stats are off. */
    public static void resetStats() {
        ExerisCarrierScheduler inst = instance;
        if (inst == null || !inst.stats) {
            return;
        }
        inst.schedulerStats.reset();
        for (int i = 0; i < inst.group.size(); i++) {
            inst.group.carrier(i).counters().reset();
        }
        resetTimerStats();
    }

    public static TimerStats timerStats() {
        return new TimerStats(TIMERS_SCHEDULED.sum(), TIMERS_CANCELLED.sum());
    }

    public static void resetTimerStats() {
        TIMERS_SCHEDULED.reset();
        TIMERS_CANCELLED.reset();
    }

    @Override
    public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (!timerCount) {
            return VirtualThreadScheduler.super.schedule(task, delay, unit);
        }
        Future<?> scheduled = VirtualThreadScheduler.super.schedule(task, delay, unit);
        if (scheduled == null) {
            return null;
        }
        TIMERS_SCHEDULED.increment();
        return new CountingFuture<>(scheduled);
    }

    private static final class CountingFuture<V> implements Future<V> {
        private final Future<V> delegate;

        @SuppressWarnings("unchecked")
        CountingFuture(Future<?> delegate) {
            this.delegate = (Future<V>) delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            if (cancelled) {
                TIMERS_CANCELLED.increment();
            }
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }
    }

    /**
     * Hands a task to its carrier. With {@code selfWakeup} a waker that is itself running on the
     * target carrier pushes to the carrier's owner side; every other waker, including one that does
     * not resolve to a carrier, goes through the carrier's inbox and wakes it.
     */
    private void dispatch(ExerisCarrierThread carrier, VirtualThreadTask task) {
        if (selfWakeup && CarrierResolver.mounted() == carrier) {
            carrier.enqueueLocal(task);
        } else {
            carrier.enqueue(task);
        }
    }

    /**
     * As {@link #dispatch}, counting how the waker related to the target carrier. The waker is
     * resolved only when {@code selfWakeup} asks for it, so the other modes count every wakeup as
     * remote.
     */
    private void dispatchCounted(ExerisCarrierThread carrier, VirtualThreadTask task) {
        if (!selfWakeup) {
            schedulerStats.continueInboxRemote.increment();
            carrier.enqueue(task);
            return;
        }
        ExerisCarrierThread waker = CarrierResolver.mounted();
        if (waker == carrier) {
            schedulerStats.continueLocal.increment();
            carrier.enqueueLocal(task);
        } else {
            (waker != null ? schedulerStats.continueInboxCarrier : schedulerStats.continueInboxRemote).increment();
            carrier.enqueue(task);
        }
    }

    /** The carrier the calling thread executes on; {@code null} when it is not on one. */
    private ExerisCarrierThread resolveCurrent() {
        return resolveMounted ? CarrierResolver.mounted() : ExerisCarrierThread.currentCarrier();
    }

    public static ExerisCarrierScheduler instance() {
        return ensureInstalled();
    }

    public static ExerisCarrierGroup group() {
        ExerisCarrierScheduler scheduler = ensureInstalled();
        return scheduler != null ? scheduler.group : null;
    }

    public ExerisCarrierGroup carrierGroup() {
        return group;
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        if (task.attachment() instanceof CarrierSchedulingContext context) {
            ExerisCarrierThread carrier = context.carrier();
            if (carrier != null) {
                if (stats) {
                    schedulerStats.startFactory.increment();
                }
                dispatch(carrier, task);
                return;
            }
            task.attach(null);
        } else {
            if (pollerRoutePreferred && task.preferredCarrier() instanceof CarrierWorkerThread preferred) {
                ExerisCarrierThread carrier = preferred.carrier();
                CarrierSchedulingContext context = new CarrierSchedulingContext(carrier.id(), carrier, true);
                context.setVThreadId(task.thread().threadId());
                task.attach(context);
                if (stats) {
                    schedulerStats.startPollerPreferred.increment();
                }
                dispatch(carrier, task);
                return;
            }
            ExerisCarrierThread currentCarrier =
                    (perCarrierPollers || assignAllVThreads) ? resolveCurrent() : null;
            if (perCarrierPollers && currentCarrier != null && task.thread().getName() != null
                    && task.thread().getName().endsWith("-Read-Poller")) {
                CarrierSchedulingContext context =
                        new CarrierSchedulingContext(currentCarrier.id(), currentCarrier);
                context.setVThreadId(task.thread().threadId());
                task.attach(context);
                if (stats) {
                    schedulerStats.startPollerName.increment();
                }
                dispatch(currentCarrier, task);
                return;
            }
            if (assignAllVThreads && group != null && group.size() > 0) {
                ExerisCarrierThread carrier;
                if (placeP2c) {
                    carrier = group.selectShorterOfTwo(schedulerStats);
                    if (stats) {
                        schedulerStats.startP2c.increment();
                    }
                } else {
                    carrier = currentCarrier != null ? currentCarrier : group.selectCarrier();
                    if (stats) {
                        countPlacement(currentCarrier != null);
                    }
                }
                CarrierSchedulingContext context =
                        new CarrierSchedulingContext(carrier.id(), carrier);
                context.setVThreadId(task.thread().threadId());
                task.attach(context);
                dispatch(carrier, task);
                return;
            }
        }
        if (stats) {
            schedulerStats.startBuiltin.increment();
        }
        builtinScheduler.onStart(task);
    }

    /** Counts a placement made without {@code place=p2c}: the starter's own carrier, or a random one. */
    private void countPlacement(boolean fromCurrent) {
        if (!fromCurrent) {
            schedulerStats.startRandom.increment();
        } else if (resolveMounted) {
            schedulerStats.startCurrentMounted.increment();
        } else {
            schedulerStats.startCurrentScope.increment();
        }
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        if (task.attachment() instanceof CarrierSchedulingContext context) {
            ExerisCarrierThread carrier = context.carrier();
            if (carrier != null) {
                if (stats) {
                    dispatchCounted(carrier, task);
                } else {
                    dispatch(carrier, task);
                }
                return;
            }
            task.attach(null);
        }
        if (stats) {
            schedulerStats.continueBuiltin.increment();
        }
        builtinScheduler.onContinue(task);
    }

    static ExerisCarrierScheduler ensureInstalled() {
        ExerisCarrierScheduler inst = instance;
        if (inst != null) {
            return inst;
        }
        Thread.ofVirtual().unstarted(() -> { }).start();
        return instance;
    }

    public static boolean isAvailable() {
        return ensureInstalled() != null;
    }
}
