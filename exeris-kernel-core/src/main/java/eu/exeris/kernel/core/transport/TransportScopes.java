/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * @since 0.5.0
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

    private TransportScopes() {
    }
}
