/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.projection;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;

/**
 * Core: Contract for a single event projection handler.
 *
 * <h2>RAII Contract</h2>
 * <p>The {@link ProjectionEngine} calls {@link #apply} with the payload already
 * ref-counted for this handler. The handler MUST close the payload (via
 * try-with-resources) to decrement the refCount. Failure to close causes a
 * slab memory leak in the Enterprise tier.
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
     * @param payload    RAII payload — the handler MUST close this (try-with-resources)
     * @return the next projection state (non-null if the projection is still active)
     */
    S apply(S current, EventDescriptor descriptor, EventPayload payload);
}
