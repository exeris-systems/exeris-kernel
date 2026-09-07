/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * SPI: Single-event handler for the {@link EventBus}.
 *
 * <h2>Payload Lifecycle Contract</h2>
 * <p>The {@link EventPayload} passed to {@link #handle} is alive for the duration
 * of the call. The {@link EventBus} has already adjusted the refCount to account
 * for the number of subscribers before invoking handlers, so a handler never has to
 * reason about how many siblings it has — it settles its own single reference.
 *
 * <p><b>Thread confinement:</b> any thread — the bus may invoke {@link #handle}
 * concurrently on several virtual threads, so an implementation carries its own
 * synchronisation for any state it shares between invocations
 * <p><b>Ownership:</b> the handler owns exactly the one reference it is handed and owes one
 * {@link EventPayload#close()} for it; a reference passed onward must be
 * {@link EventPayload#retain()}ed first, and the receiver then owes that close
 *
 * @implSpec An implementation is thread-safe: the same handler instance may be executing
 *           {@link #handle} on several virtual threads at once. It closes every payload it is
 *           given — directly, or by retaining before handing the payload to a fork that closes
 *           it. It does not read {@link EventPayload#segment()} after the close that took the
 *           reference count to zero.
 * @apiNote Wrap the body in {@code try (payload) { … }} and the close takes care of itself. On an
 *          off-heap binding a missed close is a slab-pool leak; on a heap binding it is invisible
 *          until the same handler is deployed against one that pools.
 * @implNote On the heap-backed Community binding retain and close are lightweight and the GC
 *           reclaims the bytes regardless; on an off-heap Enterprise binding the segment is valid
 *           only while the reference count is above zero, and reading it afterwards is undefined
 *           behaviour because the slab may already back another event.
 * @since 0.5
 * @see EventBus#subscribe(String, EventHandler)
 * @see EventPayload
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * Consumes one delivered event, taking ownership of the single payload reference the bus
     * hands over with it.
     *
     * @param descriptor routing metadata — Valhalla-ready, always valid, never null
     * @param payload    RAII-managed payload bytes — valid for this call's duration; never null
     *                   (use {@link EventPayload#empty()} for no-data events)
     * @apiNote The recommended shape closes the payload by construction:
     *          {@snippet lang="java" :
     *          void handle(EventDescriptor descriptor, EventPayload payload) {
     *              try (payload) {
     *                  MemorySegment bytes = payload.segment();
     *                  // ... process ...
     *              }
     *          }
     *          }
     */
    void handle(EventDescriptor descriptor, EventPayload payload);
}
