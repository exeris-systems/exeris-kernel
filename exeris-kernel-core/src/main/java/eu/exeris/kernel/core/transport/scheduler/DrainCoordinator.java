/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: counts the streams a graceful shutdown must actually wait for, and tells protocol layers when
 * that shutdown has begun.
 *
 * <h2>Why this is not the admission counter</h2>
 * <p>{@link AdmissionController#activeStreamCount()} answers a capacity question: how many
 * Virtual-Thread-per-stream handlers are running, which is what admission and load-shedding must
 * respect. For a keep-alive connection that handler runs for the whole connection — correct for
 * capacity, wrong for draining. An idle connection occupies a slot and has nothing being served, and
 * it will not go away on its own; that is what keep-alive means. Waiting on it burns the whole drain
 * deadline, and a container runtime SIGKILLs the process mid-shutdown on every rollout.
 *
 * <h2>Busy by default — the direction that matters</h2>
 * <p>A stream is <b>busy from the moment it starts</b> and stays busy unless its protocol says
 * otherwise. Only a protocol that knows it is between units of work — an HTTP/1.1 loop parked on the
 * next request — reports itself idle.
 *
 * <p>The default is load-bearing, and the opposite default is a trap this class was written with and
 * then corrected. Counting only work that protocols explicitly declare means a protocol that declares
 * nothing looks finished, so the drain completes at once and teardown severs a handler still writing
 * its response — which is the exact regression graceful shutdown exists to prevent. Defaulting to busy
 * costs an unparticipating protocol a wait; defaulting to idle costs it its response.
 *
 * <h2>Sealing — why observing zero is not enough</h2>
 * <p>The busy count reaching zero and the shutdown proceeding to teardown must be one step. Read as
 * two, a request that arrived while the drain was looking flips the count back to one on an engine
 * already committed to tearing down, and the stream serving it is severed. That window is not closable
 * by asking protocols to check {@link #isDraining()} first — a request already sitting in the socket
 * buffer when the drain began has nobody left to ask, and the same reasoning that makes busy the
 * default applies here: a protocol that does not participate must not be able to silently re-arm.
 *
 * <p>So {@link #sealIfIdle()} is a compare-and-set on the count itself. Once it succeeds no further
 * count may be taken, {@link StreamWork#markBusy()} answers {@code false}, and the protocol layer is
 * expected to close rather than serve. That is the honest reading of a connection we have already told
 * the peer to close: a request arriving after that is racing our teardown, not being dropped by it.
 *
 * <h2>Allocation</h2>
 * <p>One shared instance per engine plus one {@link StreamWork} handle per stream — the same order as
 * the Virtual Thread PAQS already spawns per stream, and nothing per request. The per-handle flag is
 * compare-and-set — see {@link StreamWork} for why it cannot rely on thread confinement — while the
 * shared count takes plain {@code getAndAdd} on the per-request path and compare-and-set only in
 * {@link #sealIfIdle()}, which runs once per drain.
 *
 * @since 0.11.0
 */
public final class DrainCoordinator {

    /**
     * Seal marker: <b>any negative count means sealed</b>, and this is the value the seal writes.
     *
     * <p>A range rather than one exact value, so the hot path can stay on {@code getAndAdd}. A single
     * sentinel forces every mutator into a read-then-compare-and-set loop, because a blind increment
     * or decrement would step off the exact value — and on the H1 keep-alive path
     * {@link StreamWork#markIdle()} and {@link StreamWork#markBusy()} run <em>per request</em> against
     * one per-engine counter, so that is the difference between an unconditional {@code lock xadd} and
     * a loop that retries under contention. With up to {@code maxConnections} streams on a handful of
     * carriers hammering one cache line, retry behaviour is exactly what degrades.
     *
     * <p>Half of {@link Integer#MIN_VALUE} rather than {@code MIN_VALUE} itself, so the marker has
     * roughly a billion of headroom in both directions. Nothing realistic approaches it: post-seal
     * decrements are bounded by the streams that were in flight, and transient increments by the
     * acquires racing the seal — both bounded by the connection ceiling, which is orders of magnitude
     * below the margin. The old exact sentinel had the opposite property: it sat one decrement away
     * from wrapping to {@link Integer#MAX_VALUE}.
     */
    private static final int SEALED = Integer.MIN_VALUE / 2;

    private static final VarHandle BUSY_STREAMS;
    private static final VarHandle DRAINING;
    private static final VarHandle BUSY_AT_SEAL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            BUSY_STREAMS = lookup.findVarHandle(DrainCoordinator.class, "busyStreams", int.class);
            DRAINING = lookup.findVarHandle(DrainCoordinator.class, "draining", boolean.class);
            BUSY_AT_SEAL = lookup.findVarHandle(DrainCoordinator.class, "busyAtSeal", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused") // VarHandle-accessed
    private volatile int busyStreams;

    @SuppressWarnings("unused") // VarHandle-accessed
    private volatile boolean draining;

    @SuppressWarnings("unused") // VarHandle-accessed
    private volatile int busyAtSeal;

    /**
     * Registers a starting stream as busy.
     *
     * <p>Called by {@link PaqsScheduler} for every admitted stream, before the handler runs.
     *
     * <p>After {@link #sealIfIdle()} has succeeded this returns a handle that holds no count. The
     * handler still runs — {@code close()} does not stop admission, so a connection accepted moments
     * earlier is entitled to what it can get — but it cannot extend a shutdown already past its commit
     * point, and its {@link StreamWork#markBusy()} answers {@code false}.
     *
     * @return the handle the protocol layer uses to report idleness; must be closed on stream exit
     */
    public StreamWork registerStream() {
        return new StreamWork(this, tryAcquire());
    }

    /**
     * Returns how many streams are currently being served.
     *
     * @return the busy count, or {@code 0} once sealed; {@code 0} means teardown may proceed
     */
    public int busyStreams() {
        int count = (int) BUSY_STREAMS.getAcquire(this);
        // Negative means sealed, not "equal to the marker": post-seal releases drive it further down,
        // and a transient acquire nudges it up. Comparing for equality would report a sealed
        // coordinator as carrying a billion live streams.
        return count < 0 ? 0 : count;
    }

    /**
     * Commits to teardown if — and atomically with observing that — nothing is being served.
     *
     * <p>The atomicity is the whole point. {@code busyStreams() == 0} followed by teardown is two
     * steps, and a stream re-arming between them is served by a handler the next step severs.
     *
     * @return {@code true} if the count was zero and is now sealed; {@code false} if work is in flight
     */
    public boolean sealIfIdle() {
        return BUSY_STREAMS.compareAndSet(this, 0, SEALED);
    }

    /**
     * Seals unconditionally, discarding whatever is still in flight.
     *
     * <p>For the drain deadline only: once shutdown has decided to proceed regardless, letting streams
     * keep taking counts serves nothing and lets a late arrival believe it is protected.
     */
    public void sealNow() {
        int previous = (int) BUSY_STREAMS.getAndSet(this, SEALED);
        if (previous < 0) {
            // Already sealed, so `previous` is the SEALED sentinel rather than a stream count.
            // Clamping it to 0 and recording that would overwrite the figure the seal that actually
            // happened left behind — restoring the structural zero this field was added to remove,
            // and only on the shutdowns that abandoned streams, since a clean drain records 0
            // anyway. The first seal is the one that saw the streams; it keeps the record.
            return;
        }
        // Recorded here because it is unrecoverable afterwards: a sealed count is negative, and
        // busyStreams() reports negative as 0 so a sealed coordinator does not read as a billion live
        // streams. That clamp makes "how much did the deadline abandon" unanswerable after the fact —
        // which is what made the drain JFR event's busyRemaining structurally zero on every shutdown,
        // including the ones that gave up on live streams.
        BUSY_AT_SEAL.setRelease(this, previous);
    }

    /**
     * Whether the coordinator has sealed, by either route.
     *
     * <p>A seal is terminal: {@link #sealIfIdle()} compares against zero and a sealed count is
     * negative, so nothing re-opens it. Callers use this to avoid waiting for a drain that has
     * already finished.
     */
    public boolean isSealed() {
        return (int) BUSY_STREAMS.getVolatile(this) < 0;
    }

    /**
     * How many streams were still being served when the coordinator sealed.
     *
     * <p>{@code 0} after a clean drain — {@link #sealIfIdle()} only succeeds on zero — and the
     * abandoned count after {@link #sealNow()} took the deadline. Reportable telemetry, not a control
     * signal: by the time it is non-zero, shutdown has already decided to proceed.
     *
     * @return the busy count at seal time, or {@code 0} if not sealed
     */
    public int busyAtSeal() {
        return (int) BUSY_AT_SEAL.getAcquire(this);
    }

    /**
     * Takes one count unless sealed.
     *
     * <p>Add first, then inspect what was there — not read-then-compare-and-set. This runs per request
     * on the H1 keep-alive path, so the difference is an unconditional {@code getAndAdd} against a loop
     * that retries under contention. It is only available because {@link #SEALED} marks a range: the
     * speculative {@code +1} on a sealed count lands harmlessly inside it and is taken straight back.
     *
     * @return {@code true} if a count was taken and must later be released
     */
    private boolean tryAcquire() {
        int previous = (int) BUSY_STREAMS.getAndAdd(this, 1);
        if (previous < 0) {
            // Sealed. Take the count back; the marker's headroom absorbs the round trip, and this
            // branch is only reachable after teardown has committed.
            BUSY_STREAMS.getAndAdd(this, -1);
            return false;
        }
        return true;
    }

    /**
     * Signals that graceful shutdown has begun.
     *
     * <p>Protocol layers read this to stop extending connections they would otherwise keep alive, and
     * to tell the peer so — an HTTP/1.1 codec responds {@code Connection: close}. Without that, a
     * well-behaved client has no way to know it should release the connection.
     */
    public void markDraining() {
        DRAINING.setRelease(this, true);
    }

    /**
     * Returns whether graceful shutdown has begun.
     *
     * @return {@code true} once {@link #markDraining()} has been called
     */
    public boolean isDraining() {
        return (boolean) DRAINING.getAcquire(this);
    }

    /**
     * Returns one count, unless the coordinator is sealed.
     *
     * <p>Unconditional, and safe only because {@link #SEALED} marks a <em>range</em>. {@link #sealNow()}
     * discards whatever was in flight, so a handler that finishes after the deadline still calls this;
     * the decrement drives the marker further negative, which still reads as sealed. An exact sentinel
     * at {@link Integer#MIN_VALUE} sat one decrement from wrapping to {@link Integer#MAX_VALUE} —
     * no longer sealed, so acquisition resumed and a stream could report itself busy during teardown.
     *
     * <p>Once sealed the count is not a count, and the handle's flag and the owner's count are
     * deliberately allowed to disagree from that point on.
     */
    private void release() {
        BUSY_STREAMS.getAndAdd(this, -1);
    }

    /**
     * One stream's contribution to the busy count.
     *
     * <h2>Why this is not thread-confined</h2>
     * <p>An earlier version declared the flag confined to the stream's own thread and left it a plain
     * field. The mechanism contradicted the declaration: the handle reaches protocol code only through
     * {@link eu.exeris.kernel.core.transport.TransportScopes#STREAM_WORK}, a {@code ScopedValue} —
     * chosen precisely so it survives down the stack and across a task boundary. A parameter or a field
     * would have expressed confinement; this expresses the opposite.
     *
     * <p>The reachable route does not depend on {@code StructuredTaskScope}, and saying so matters
     * because the two distribution tracks disagree about it: the {@code preview} artifact keeps STS
     * (whose {@code fork()} inherits {@code ScopedValue} bindings), while the default line must stay
     * preview-clean for 1.0 GA. What holds on <em>both</em> is {@link StreamExecutionBackend}: the PAQS
     * seam at which an admitted stream's root task is started, contractually required to preserve
     * {@code ScopedValue} bindings across whatever thread a backend chooses
     * ({@code AbstractPaqsSchedulerTck#customExecutionBackendPreservesScopedValueBindings}). Its
     * forward consumers are the ones that run the task somewhere the scheduler did not create — an
     * Enterprise locality-aware backend, and the post-1.0 DST simulation scheduler.
     *
     * <p>Both failure modes destroy what this class is for. Two threads observing {@code busy == true}
     * in {@link #markIdle()} both release, the count falls below the truth, the drain finishes early
     * and teardown severs a handler mid-response — the exact regression graceful shutdown exists to
     * prevent. Two observing {@code busy == false} in {@link #markBusy()} both take a count, and the
     * drain never reaches zero. Neither is reachable by any test that is not a stress harness, and
     * neither is visible to ArchUnit or PMD, so the guarantee has to be a property of the type.
     *
     * <h2>Invariant</h2>
     * <p>Until the owner is sealed, this handle holds exactly {@code busy ? 1 : 0} counts on it. Every
     * transition of the flag is a compare-and-set paired with exactly one counter operation, and the
     * loser of a concurrent {@link #markBusy()} returns the count it took rather than leaving it
     * stranded.
     *
     * <p>Sealing ends that correspondence deliberately: {@link #sealNow()} discards the counts of
     * streams still in flight, so a handle can be {@code busy} while the owner holds nothing for it.
     * From then on the flag describes only this stream's own view, and the counter's value carries no
     * meaning to restore — which is why {@link #release()} may decrement it unconditionally. The
     * marker is a range, so a post-seal decrement lands further inside it (see {@link #SEALED}); it is
     * not a value a mutator has to steer around.
     */
    public static final class StreamWork implements AutoCloseable {

        private static final VarHandle BUSY;

        static {
            try {
                BUSY = MethodHandles.lookup().findVarHandle(StreamWork.class, "busy", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final DrainCoordinator owner;

        /** Written only through {@link #BUSY}; the plain reads below are the fast paths. */
        private volatile boolean busy;

        private StreamWork(DrainCoordinator owner, boolean busy) {
            this.owner = owner;
            this.busy = busy;
        }

        /** Reports that this stream is between units of work and must not hold shutdown open. */
        public void markIdle() {
            if (BUSY.compareAndSet(this, true, false)) {
                owner.release();
            }
        }

        /**
         * Reports that this stream has work in flight again.
         *
         * <p>The count is taken <em>before</em> the flag is set, never the other way round. Setting the
         * flag first and reverting on failure would expose a window where a concurrent
         * {@link #markIdle()} sees {@code busy == true} and releases a count this handle never took.
         *
         * @return {@code false} if the drain has already committed to teardown, in which case the
         *         protocol layer must close rather than serve — the stream will not be waited for
         */
        public boolean markBusy() {
            if (busy) {
                return true;
            }
            if (!owner.tryAcquire()) {
                return false;
            }
            if (!BUSY.compareAndSet(this, false, true)) {
                // Another markBusy won; it already holds the one count this handle is entitled to.
                owner.release();
            }
            return true;
        }

        /** Releases this stream's contribution; idempotent with {@link #markIdle()}. */
        @Override
        public void close() {
            markIdle();
        }
    }
}
