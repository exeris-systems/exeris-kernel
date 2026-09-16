/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport;

import eu.exeris.kernel.spi.transport.StreamPriority;

/**
 * Core: Static registry of {@link ScopedValue} slots for transport cross-cutting context.
 *
 * <h2>JEP 506 — Scoped Values (No ThreadLocal)</h2>
 * <p>All values here are propagated via {@link ScopedValue} bindings set by the
 * {@link eu.exeris.kernel.core.transport.scheduler.PaqsScheduler} at Virtual Thread creation time.
 * They are immutable for the duration of the stream's lifecycle. Downstream handlers
 * (persistence, graph, saga) read them without requiring method parameters.
 *
 * <h2>The Wall</h2>
 * <p>These slots live in {@code exeris-kernel-core} and are read by
 * {@code exeris-kernel-community} and {@code exeris-kernel-enterprise}
 * implementations. They MUST NOT expose any protocol-specific state
 * (no QUIC stream IDs, no TCP socket FDs, no io_uring ring pointers).
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a utility class of constants — no instances, no identity operations.
 *
 * @since 0.5
 */
public final class TransportScopes {

    /**
     * The {@link StreamPriority} assigned to the current stream's Virtual Thread.
     *
     * <p>Bound by {@link eu.exeris.kernel.core.transport.scheduler.PaqsScheduler}
     * at VT creation time. Business logic and persistence layers may read this to
     * apply per-priority database timeouts or query limits.
     */
    public static final ScopedValue<StreamPriority> STREAM_PRIORITY = ScopedValue.newInstance();

    /**
     * The stream's unique kernel-assigned identifier.
     *
     * <p>Corresponds to {@link eu.exeris.kernel.spi.transport.TransportStream#streamId()}.
     * Available for trace correlation in JFR events and structured logs.
     */
    public static final ScopedValue<Long> STREAM_ID = ScopedValue.newInstance();

    /**
     * The display name of the {@link eu.exeris.kernel.spi.transport.TransportEngine}
     * that delivered this stream.
     *
     * <p>Used in JFR events and diagnostic output without requiring downcasting.
     */
    public static final ScopedValue<String> ENGINE_NAME = ScopedValue.newInstance();

    /**
     * The engine's {@link eu.exeris.kernel.core.transport.scheduler.DrainCoordinator}.
     *
     * <p>Bound by {@code PaqsScheduler} around every stream handler, so a protocol layer can mark
     * the work a graceful shutdown must wait for without the transport having to know what a
     * "request" is. Read defensively — a carrier that does not bind it leaves the slot unbound, and
     * a protocol that does not read it simply never marks, which drains immediately rather than
     * hanging.
     *
     * @since 0.11
     */
    public static final ScopedValue<eu.exeris.kernel.core.transport.scheduler.DrainCoordinator>
            DRAIN_COORDINATOR = ScopedValue.newInstance();

    /**
     * This stream's {@link eu.exeris.kernel.core.transport.scheduler.DrainCoordinator.StreamWork}.
     *
     * <p>A protocol layer that can tell when it is between units of work reports idleness through
     * this handle, so an idle connection stops holding graceful shutdown open. A protocol that never
     * reports stays busy for the stream's lifetime, which is the safe default.
     *
     * @since 0.11
     */
    public static final ScopedValue<
            eu.exeris.kernel.core.transport.scheduler.DrainCoordinator.StreamWork> STREAM_WORK =
            ScopedValue.newInstance();

    private TransportScopes() {
    }
}
