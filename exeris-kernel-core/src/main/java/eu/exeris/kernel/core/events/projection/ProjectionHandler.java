/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.projection;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;

/**
 * Core: Contract for a single event projection handler.
 *
 * <h2>RAII Contract</h2>
 * <p>{@link Projection} closes the {@link eu.exeris.kernel.spi.events.EventPayload}
 * after {@link #apply} returns (or throws). The handler MUST NOT close the payload —
 * doing so would double-release the refCount and cause over-release bugs in
 * Enterprise slab implementations.
 *
 * <h2>Idempotency</h2>
 * <p>Projection handlers MUST be idempotent — the EventBus delivers at-least-once.
 * Use {@link EventDescriptor#eventIdHigh()} and {@link EventDescriptor#eventIdLow()}
 * as a stable deduplication key.
 *
 * @param <S> the projection state type (must be immutable or thread-confined)
 * @since 0.5.0
 */
@FunctionalInterface
public interface ProjectionHandler<S> {

    /**
     * Applies the event to the current projection state and returns the next state.
     *
     * @param current    the current state (may be {@code null} for the first event)
     * @param descriptor event routing metadata
     * @param payload    RAII payload — owned by {@link Projection}; handler MUST NOT close
     * @return the next projection state (non-null if the projection is still active)
     */
    S apply(S current, EventDescriptor descriptor, EventPayload payload);
}
