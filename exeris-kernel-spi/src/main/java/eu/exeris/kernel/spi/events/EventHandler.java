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
 * for the number of subscribers before invoking handlers:
 * <ul>
 *   <li>Each handler <b>MUST</b> call {@code payload.close()} — or use
 *       try-with-resources — to decrement the refCount when done.</li>
 *   <li>If the handler needs to retain the payload beyond the invocation scope
 *       (e.g., pass it to another {@link java.util.concurrent.StructuredTaskScope} fork),
 *       it MUST call {@code payload.retain()} <b>before</b> forking, and the fork
 *       is responsible for the matching {@code close()}.</li>
 *   <li>For Community (heap-backed): retain/close are lightweight no-ops — GC handles it.</li>
 *   <li>For Enterprise (off-heap slab): failing to close causes a slab pool exhaustion leak.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations MUST be thread-safe — handlers may be invoked concurrently
 * from multiple virtual threads (Community broadcast via {@code StructuredTaskScope}).
 *
 * <h2>Off-Heap Safety</h2>
 * <p>For Enterprise events where {@code payload.length() > 0}, the backing
 * {@link java.lang.foreign.MemorySegment} is valid <b>only</b> during the
 * {@link #handle} invocation (or while refCount > 0 after retain).
 * Accessing it after the final {@code close()} results in undefined behaviour.
 *
 * @since 0.5
 * @see EventBus#subscribe(String, EventHandler)
 * @see EventPayload
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * Handles a single event.
     *
     * <p><b>Recommended pattern (try-with-resources):</b>
     * <pre>{@code
     * void handle(EventDescriptor descriptor, EventPayload payload) {
     *     try (payload) {
     *         MemorySegment bytes = payload.segment();
     *         // ... process ...
     *     }
     * }
     * }</pre>
     *
     * @param descriptor routing metadata — Valhalla-ready, always valid, never null
     * @param payload    RAII-managed payload bytes — valid for this call's duration; never null
     *                   (use {@link EventPayload#empty()} for no-data events)
     */
    void handle(EventDescriptor descriptor, EventPayload payload);
}
