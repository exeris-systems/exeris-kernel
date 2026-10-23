/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated carrier thread with local MPSC queue and Seastar-style park/unpark coordination.
 */
public final class ExerisCarrierThread {
    public static final ScopedValue<ExerisCarrierThread> CURRENT_CARRIER = ScopedValue.newInstance();
    private static final System.Logger LOG = System.getLogger(ExerisCarrierThread.class.getName());

    private static final VarHandle CARRIER_STATE;
    private static final MethodHandle SCHED_SETAFFINITY;
    private static final int RUNNING = 0;
    private static final int PARKED = 1;

    static {
        try {
            CARRIER_STATE = MethodHandles.lookup().findVarHandle(
                    ExerisCarrierThread.class, "carrierState", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        MethodHandle setAffinity = null;
        try {
            var opt = Linker.nativeLinker().defaultLookup().find("sched_setaffinity");
            if (opt.isPresent()) {
                setAffinity = Linker.nativeLinker().downcallHandle(
                        opt.get(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
                );
            }
        } catch (Throwable ignored) {
            // no native linker or no sched_setaffinity symbol: affinity binding stays unavailable
        }
        SCHED_SETAFFINITY = setAffinity;
    }

    private final int id;
    private final int cpuCore;
    private final Thread carrierThread;
    private final ConcurrentLinkedQueue<Runnable> runQueue = new ConcurrentLinkedQueue<>();
    private final ThreadFactory vThreadFactory;
    private final long yieldBudgetNs;
    private final boolean factoryPreferredCarrier;
    private final long idleSpinNs;
    private final boolean readFirstWakeup;

    @SuppressWarnings("unused")
    private volatile int carrierState = RUNNING;
    private volatile Runnable pinnedPollerWakeup;
    private volatile boolean shutdown;

    public ExerisCarrierThread(int id, ExerisCarrierScheduler scheduler) {
        this(id, scheduler, -1);
    }

    public ExerisCarrierThread(int id, ExerisCarrierScheduler scheduler, int cpuCore) {
        this(id, scheduler, cpuCore, LocalityFlags.fromSystem());
    }

    ExerisCarrierThread(int id, ExerisCarrierScheduler scheduler, int cpuCore, LocalityFlags flags) {
        this.id = id;
        this.cpuCore = cpuCore;
        this.yieldBudgetNs = flags.yieldBudgetNs();
        this.factoryPreferredCarrier = flags.factoryPreferredCarrier();
        this.idleSpinNs = flags.idleSpinNs();
        this.readFirstWakeup = flags.wakeup() == LocalityFlags.Wakeup.READFIRST;
        this.vThreadFactory = createVirtualThreadFactory(scheduler);
        this.carrierThread = new CarrierWorkerThread(this::runLoop, "exeris-carrier-" + id, this);
        this.carrierThread.setDaemon(true);
        this.carrierThread.start();
    }

    public static ExerisCarrierThread currentCarrier() {
        if (CURRENT_CARRIER.isBound()) {
            return CURRENT_CARRIER.get();
        }
        Thread curr = Thread.currentThread();
        if (curr instanceof CarrierWorkerThread worker) {
            return worker.carrier();
        }
        return null;
    }

    public int id() {
        return id;
    }

    public Thread carrierThread() {
        return carrierThread;
    }

    public ThreadFactory virtualThreadFactory() {
        return vThreadFactory;
    }

    public void enqueue(Runnable task) {
        runQueue.offer(task);
        wakeup();
    }

    /**
     * Unparks the carrier if it is parked. Callers must have published the work to the run queue
     * with a volatile write or read-modify-write before calling.
     *
     * <p>With {@code wakeup=readFirst} the state is read before it is swapped, and only a parked
     * carrier is swapped. That is correct for the same reason the swap is: the consumer moves the
     * state to PARKED with a volatile compare-and-set and only then re-reads the queue, while the
     * producer's queue publication precedes its state read, and all of these are volatile accesses
     * in one synchronization order. Either the producer reads PARKED and unparks, or the consumer's
     * compare-and-set follows that read in the order and its queue re-read follows the producer's
     * publication, so the consumer finds the work and does not park.
     */
    public void wakeup() {
        if (readFirstWakeup && (int) CARRIER_STATE.getVolatile(this) != PARKED) {
            return;
        }
        if ((int) CARRIER_STATE.getAndSet(this, RUNNING) == PARKED) {
            LockSupport.unpark(carrierThread);
            Runnable wakeup = pinnedPollerWakeup;
            if (wakeup != null) {
                wakeup.run();
            }
        }
    }

    public boolean tryParkPoller() {
        return CARRIER_STATE.compareAndSet(this, RUNNING, PARKED);
    }

    public boolean canParkPoller() {
        return (int) CARRIER_STATE.getAcquire(this) == PARKED && runQueue.isEmpty();
    }

    public void unparkPoller() {
        CARRIER_STATE.setVolatile(this, RUNNING);
    }

    public void registerPinnedPoller(Runnable wakeup) {
        this.pinnedPollerWakeup = wakeup;
    }

    private ThreadFactory createVirtualThreadFactory(ExerisCarrierScheduler scheduler) {
        var unstartedBuilder = Thread.ofVirtual();
        return runnable -> {
            var context = new CarrierSchedulingContext(id, this);
            var vTask = scheduler.newThread(unstartedBuilder, factoryPreferredCarrier ? carrierThread : null, () -> {
                ScopedValue.where(CURRENT_CARRIER, this).run(runnable);
            });
            if (vTask != null) {
                context.setVThreadId(vTask.thread().threadId());
                try {
                    vTask.attach(context);
                } catch (UnsupportedOperationException ignored) {
                    // the VM was not booted with a custom scheduler: the built-in task has no attachment
                }
                return vTask.thread();
            }
            return unstartedBuilder.unstarted(() -> {
                ScopedValue.where(CURRENT_CARRIER, this).run(runnable);
            });
        };
    }

    private void runLoop() {
        if (cpuCore >= 0) {
            bindToCore(cpuCore);
        }
        ScopedValue.where(CURRENT_CARRIER, this).run(() -> {
            while (!shutdown) {
                drainTasks();
                if (runQueue.isEmpty()) {
                    if (idleSpinNs > 0 && spinForWork()) {
                        continue;
                    }
                    if (CARRIER_STATE.compareAndSet(this, RUNNING, PARKED)) {
                        try {
                            // shutdown is re-read with the queue: a request that raced the state
                            // change must not be slept through
                            if (runQueue.isEmpty() && !shutdown) {
                                LockSupport.park();
                            }
                        } finally {
                            CARRIER_STATE.setVolatile(this, RUNNING);
                        }
                    }
                }
            }
        });
    }

    /**
     * Keeps the carrier RUNNING and re-polls the queue for up to the idle spin budget. A producer
     * that enqueues meanwhile sees RUNNING and spends no unpark. Returns whether work (or shutdown)
     * arrived; on false the caller proceeds to the unchanged park handshake.
     */
    private boolean spinForWork() {
        long deadline = System.nanoTime() + idleSpinNs;
        do {
            if (!runQueue.isEmpty() || shutdown) {
                return true;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return false;
    }

    private void drainTasks() {
        long start = System.nanoTime();
        Runnable task;
        while ((task = runQueue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Error executing carrier task", t);
            }
            if (System.nanoTime() - start > yieldBudgetNs) {
                break;
            }
        }
    }

    public static boolean bindToCore(int core) {
        return bindToCores(core);
    }

    public static boolean bindToCores(int... cores) {
        if (SCHED_SETAFFINITY == null || cores == null || cores.length == 0) {
            return false;
        }
        // CHECKSTYLE:OFF DirectArena — one-off native mask allocation for sched_setaffinity
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(128);
            for (int core : cores) {
                if (core >= 0) {
                    long word = mask.get(ValueLayout.JAVA_LONG, (core / 64) * 8);
                    word |= (1L << (core % 64));
                    mask.set(ValueLayout.JAVA_LONG, (core / 64) * 8, word);
                }
            }
            int ret = (int) SCHED_SETAFFINITY.invokeExact(0, 128L, mask);
            if (ret == 0) {
                LOG.log(System.Logger.Level.INFO,
                        "[ExerisCarrierThread] {0} pinned to CPU core(s) {1}",
                        Thread.currentThread().getName(), java.util.Arrays.toString(cores));
                return true;
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "[ExerisCarrierThread] sched_setaffinity returned {0} for cores {1}",
                        ret, java.util.Arrays.toString(cores));
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "[ExerisCarrierThread] Failed to set affinity to cores " + java.util.Arrays.toString(cores), t);
        }
        // CHECKSTYLE:ON
        return false;
    }

    public void shutdown() {
        this.shutdown = true;
        wakeup();
    }

    static final class CarrierWorkerThread extends Thread {
        private final ExerisCarrierThread carrier;

        CarrierWorkerThread(Runnable target, String name, ExerisCarrierThread carrier) {
            super(target, name);
            this.carrier = carrier;
        }

        ExerisCarrierThread carrier() {
            return carrier;
        }
    }
}
