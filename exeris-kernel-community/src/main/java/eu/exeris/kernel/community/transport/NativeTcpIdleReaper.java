/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reclaims connections that have moved no bytes for {@code transport.idleTimeoutMillis}.
 *
 * <p>One instance per reactor, and every field is touched only by that reactor's thread — the
 * sweep runs inside {@code NativeTcpReactor.runLoop} after the selected keys are dispatched, so
 * no timer thread, no scheduled task and no shared cursor exist. That placement is the reason
 * this is affordable: the reactor already wakes at least every 100 ms for its bounded
 * {@code select}, and the sweep rides a wake-up that was going to happen anyway.
 *
 * <p><b>The sweep is gated, because {@code select} returning is not a reason to walk every
 * key.</b> Under load a reactor returns from {@code select} thousands of times a second, and an
 * ungated O(keys) walk on each would put a scan of the whole connection set on the hot path to
 * enforce a limit measured in seconds. The gate is {@code idleTimeout / 4}, clamped to
 * [250 ms, 5 s] — the same shape, and for the same reason, as
 * {@code CommunityTenantPoolRegistry}'s reclaim cadence.
 *
 * <p><b>Teardown is abortive</b>, reusing {@code NativeTcpCarrier.closeKeyStream} rather than a
 * second lifecycle. A graceful close is the wrong instrument here for the reason that method's
 * own contract states: it defers until queued egress drains, and a peer that has gone quiet is
 * exactly the peer that may never drain it.
 *
 * <p><b>Allocation:</b> allocates one {@code List<SelectionKey>}, sized to the expired count, per
 * sweep that finds at least one expired connection; a sweep that finds none — every sweep on a
 * healthy reactor — allocates nothing.
 * <p><b>Thread confinement:</b> owner thread — {@link #nextSweepAtNanos} is written and read only
 * by the one reactor thread that calls {@link #sweep}; no other method touches mutable state.
 * <p><b>Ownership:</b> owns no buffer or native resource of its own; a connection this reaper
 * identifies as expired is torn down by {@code NativeTcpCarrier.closeKeyStream}, not by this class.
 *
 * @since 0.12
 */
final class NativeTcpIdleReaper {

    /** Contract value of {@code idleTimeoutMillis} that disables reclamation entirely. */
    /* default */ static final long TIMEOUT_DISABLED = 0L;

    private static final long MIN_SWEEP_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long MAX_SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final int SWEEP_DIVISOR = 4;

    private final long idleTimeoutNanos;
    private final long sweepIntervalNanos;

    /** Reactor-thread confined; deliberately not volatile, and never read off that thread. */
    private long nextSweepAtNanos;

    private NativeTcpIdleReaper(long idleTimeoutNanos, long sweepIntervalNanos, long startNanos) {
        this.idleTimeoutNanos = idleTimeoutNanos;
        this.sweepIntervalNanos = sweepIntervalNanos;
        this.nextSweepAtNanos = startNanos + sweepIntervalNanos;
    }

    /**
     * Builds a reaper for one reactor. A non-positive timeout yields a disabled reaper whose
     * {@link #sweep} is a single predictable branch.
     *
     * @param idleTimeoutMillis the configured timeout; {@code 0} (or negative, which
     *                          {@code TransportConfig} already refuses) disables reclamation
     * @return a reaper owned by the calling reactor
     */
    /* default */ static NativeTcpIdleReaper forTimeout(long idleTimeoutMillis) {
        if (idleTimeoutMillis <= TIMEOUT_DISABLED) {
            return new NativeTcpIdleReaper(TIMEOUT_DISABLED, MAX_SWEEP_INTERVAL_NANOS, 0L);
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis);
        long interval = Math.clamp(timeoutNanos / SWEEP_DIVISOR,
                MIN_SWEEP_INTERVAL_NANOS, MAX_SWEEP_INTERVAL_NANOS);
        return new NativeTcpIdleReaper(timeoutNanos, interval, System.nanoTime());
    }

    /** Whether this reaper reclaims anything at all. */
    /* default */ boolean enabled() {
        return idleTimeoutNanos > TIMEOUT_DISABLED;
    }

    /** The configured timeout in nanoseconds, or {@code 0} when disabled. Test seam. */
    /* default */ long idleTimeoutNanos() {
        return idleTimeoutNanos;
    }

    /**
     * Closes every stream on {@code selector} whose last read or write is older than the
     * timeout, at most once per sweep interval.
     *
     * <p>Victims are collected before any is closed. Cancelling a key does not remove it from
     * {@link Selector#keys()} until the next select, so in-place teardown would not corrupt the
     * iteration today — but it makes the sweep's correctness depend on that, and the list is
     * allocated only when a victim exists, which on a healthy reactor is never.
     *
     * @param selector      the calling reactor's selector
     * @param host          the carrier owning the shared teardown path
     * @param reactorIndex  reactor index, for the emitted event
     * @param nowNanos      the reactor's current {@code System.nanoTime()} reading
     * @return the number of connections reclaimed by this call
     */
    @SuppressWarnings("PMD.CloseResource")
    // The reaper never OWNS a stream, so it must not close one. Teardown belongs to
    // NativeTcpCarrier.closeKeyStream, which cancels the key and resets the stream so
    // finishCloseIfDrained can bypass its drain gate; a try-with-resources close() here would
    // instead take the graceful path and defer on egress this peer may never drain.
    /* default */ int sweep(Selector selector, NativeTcpCarrier host, int reactorIndex, long nowNanos) {
        if (!enabled() || nowNanos - nextSweepAtNanos < 0) {
            return 0;
        }
        nextSweepAtNanos = nowNanos + sweepIntervalNanos;

        // Counted before it is collected, so the healthy case — nothing expired, which is every
        // sweep on a live reactor — allocates nothing at all, and the unhealthy one allocates
        // exactly once, exactly sized. A key that changes state between the two passes only moves
        // its reclamation to the next sweep, which is immaterial at this cadence.
        int expiredCount = 0;
        for (SelectionKey key : selector.keys()) {
            if (isExpired(key, nowNanos)) {
                expiredCount++;
            }
        }
        if (expiredCount == 0) {
            return 0;
        }

        List<SelectionKey> expired = new ArrayList<>(expiredCount);
        for (SelectionKey key : selector.keys()) {
            if (isExpired(key, nowNanos)) {
                expired.add(key);
            }
        }

        // Collected before any is closed. Cancelling a key does not remove it from
        // Selector.keys() until the next select, so in-place teardown would not corrupt the
        // iteration today — but it would make this sweep's correctness depend on that.
        for (SelectionKey key : expired) {
            NativeTcpStream stream = (NativeTcpStream) key.attachment();
            CommunityConnectionIdleTimeoutEvent.emit(
                    stream.streamId(),
                    reactorIndex,
                    TimeUnit.NANOSECONDS.toMillis(nowNanos - stream.lastActivityNanos()),
                    TimeUnit.NANOSECONDS.toMillis(idleTimeoutNanos));
            host.closeKeyStream(key);
        }
        return expired.size();
    }

    @SuppressWarnings("PMD.CloseResource") // See sweep: the reaper observes streams, it never owns them.
    private boolean isExpired(SelectionKey key, long nowNanos) {
        // The server socket's own key carries no stream attachment and must never be swept.
        return key.attachment() instanceof NativeTcpStream stream
                && nowNanos - stream.lastActivityNanos() >= idleTimeoutNanos;
    }
}
