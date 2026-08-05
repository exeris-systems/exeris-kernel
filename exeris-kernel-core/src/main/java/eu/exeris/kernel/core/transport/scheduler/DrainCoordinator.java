/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>Allocation</h2>
 * <p>One shared instance per engine plus one {@link StreamWork} handle per stream — the same order as
 * the Virtual Thread PAQS already spawns per stream, and nothing per request. The handle's own flag is
 * confined to its stream's thread, so only the shared counter is atomic.
 *
 * @since 0.11.0
 */
public final class DrainCoordinator {

    private static final VarHandle BUSY_STREAMS;
    private static final VarHandle DRAINING;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            BUSY_STREAMS = lookup.findVarHandle(DrainCoordinator.class, "busyStreams", int.class);
            DRAINING = lookup.findVarHandle(DrainCoordinator.class, "draining", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused") // VarHandle-accessed
    private volatile int busyStreams;

    @SuppressWarnings("unused") // VarHandle-accessed
    private volatile boolean draining;

    /**
     * Registers a starting stream as busy.
     *
     * <p>Called by {@link PaqsScheduler} for every admitted stream, before the handler runs.
     *
     * @return the handle the protocol layer uses to report idleness; must be closed on stream exit
     */
    public StreamWork registerStream() {
        BUSY_STREAMS.getAndAdd(this, 1);
        return new StreamWork(this);
    }

    /**
     * Returns how many streams are currently being served.
     *
     * @return the busy count; {@code 0} means shutdown may proceed to teardown
     */
    public int busyStreams() {
        return (int) BUSY_STREAMS.getAcquire(this);
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

    private void release() {
        BUSY_STREAMS.getAndAdd(this, -1);
    }

    /**
     * One stream's contribution to the busy count.
     *
     * <p>The flag is confined to the stream's own thread — the protocol loop that reports it is the
     * only caller — so the handle needs no synchronisation of its own.
     */
    public static final class StreamWork implements AutoCloseable {

        private final DrainCoordinator owner;
        private boolean busy = true;

        private StreamWork(DrainCoordinator owner) {
            this.owner = owner;
        }

        /** Reports that this stream is between units of work and must not hold shutdown open. */
        public void markIdle() {
            if (busy) {
                busy = false;
                owner.release();
            }
        }

        /** Reports that this stream has work in flight again. */
        public void markBusy() {
            if (!busy) {
                busy = true;
                BUSY_STREAMS.getAndAdd(owner, 1);
            }
        }

        /** Releases this stream's contribution; idempotent with {@link #markIdle()}. */
        @Override
        public void close() {
            markIdle();
        }
    }
}
