/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import java.lang.foreign.MemorySegment;

/**
 * SPI: RAII-managed event payload wrapper.
 *
 * <h2>Purpose</h2>
 * <p>{@code EventPayload} is the RAII counterpart to the routing-only
 * {@link EventDescriptor}. While the descriptor is a Valhalla-ready primitive
 * record used for O(1) routing, the payload carries the actual bytes and
 * owns a reference to the backing memory (heap array or off-heap slab).
 *
 * <h2>Reference Counting and Broadcast Safety</h2>
 * <p>When {@link EventBus#publish} is called with N registered handlers:
 * <ol>
 *   <li>If {@code N == 0}: {@link #close()} is called immediately — slab returned.</li>
 *   <li>If {@code N == 1}: payload passed as-is (initial refCount = 1), handler closes.</li>
 *   <li>If {@code N > 1}: {@link #retain()} is called {@code (N-1)} times before forking,
 *       so total refCount = N. Each handler's {@link #close()} decrements by one.
 *       The last close returns the backing memory to the pool.</li>
 * </ol>
 * This protocol is the responsibility of the {@link EventBus} implementation —
 * handlers receive a correctly-counted payload and simply call {@link #close()}
 * (or use try-with-resources).
 *
 * <h2>Community vs Enterprise</h2>
 * <ul>
 *   <li><b>Community</b>: backed by a heap {@code byte[]} or {@link eu.exeris.kernel.spi.memory.LoanedBuffer}.
 *       {@link #retain()} and {@link #close()} are lightweight — GC reclaims memory.</li>
 *   <li><b>Enterprise</b>: backed by an off-heap slab slot from the tiered payload pool
 *       (256 B / 4 KB / 64 KB). {@link #retain()} and {@link #close()} perform a
 *       {@link java.lang.invoke.VarHandle} CAS on a per-slab atomic refCount field.
 *       When refCount reaches 0, the slab slot is returned to its pool — zero GC.</li>
 * </ul>
 *
 * <h2>Handler Contract</h2>
 * <pre>{@code
 * // Safe pattern — try-with-resources:
 * void handle(EventDescriptor descriptor, EventPayload payload) {
 *     try (payload) {
 *         MemorySegment bytes = payload.segment();
 *         // ... process bytes ...
 *     } // auto-close → refCount--
 * }
 *
 * // Retain pattern — passing to another thread:
 * void handle(EventDescriptor descriptor, EventPayload payload) {
 *     payload.retain();   // refCount++ before fork
 *     scope.fork(() -> {
 *         try (payload) { // refCount-- when fork completes
 *             process(payload);
 *         }
 *     });
 *     // do NOT call close() here — the fork owns it
 * }
 * }</pre>
 *
 * <h2>Sentinel Value</h2>
 * <p>Use {@link #empty()} for events that carry no data (lifecycle signals, graph edges).
 * The empty sentinel has infinite refCount and no backing memory — retain/close are no-ops.
 *
 * @since 0.5.0
 * @see EventDescriptor
 * @see EventBus
 * @see EventHandler
 */
public interface EventPayload extends AutoCloseable {

    /**
     * Returns a read-only view of the payload bytes as a Panama FFM {@link MemorySegment}.
     *
     * <p><b>Community</b>: {@code MemorySegment.ofArray(backingByteArray)} — heap-backed.
     * <p><b>Enterprise</b>: segment backed by the off-heap slab at the raw address.
     *
     * <p><b>Lifetime:</b> valid only while this payload is alive ({@link #isAlive()} == true).
     * For Enterprise, accessing the segment after {@link #close()} reduces refCount to 0
     * results in undefined behaviour — the slab memory may be reused.
     *
     * @return read-only {@link MemorySegment} over the payload bytes
     * @throws IllegalStateException if this payload has already been fully released
     */
    MemorySegment segment();

    /**
     * Returns the byte length of the payload.
     *
     * <p>O(1). Always safe to call, even after {@link #close()} (for diagnostics/logging).
     *
     * @return payload length in bytes (0 for the {@link #empty()} sentinel)
     */
    int length();

    /**
     * Increments the reference count.
     *
     * <p>Call this <b>before</b> passing the payload to another thread or
     * storing it beyond the current handler invocation scope.
     *
     * <p>Community: no-op (GC manages lifetime).
     * Enterprise: VarHandle CAS increment on the slab's refCount field.
     *
     * @throws IllegalStateException if the payload has already been fully released (refCount == 0)
     */
    void retain();

    /**
     * Decrements the reference count. When the count reaches 0, the backing
     * memory is returned to the pool (Enterprise) or becomes eligible for GC (Community).
     *
     * <p>Idempotent for Community. For Enterprise: call exactly once per {@link #retain()}
     * or initial acquire. Over-releasing is a programming error and SHOULD throw
     * {@link IllegalStateException} in debug builds.
     *
     * <p>Implementations MUST emit a {@code EventPayloadReleasedEvent} JFR event when
     * refCount reaches 0 and backing memory is returned (Enterprise only).
     */
    @Override
    void close();

    /**
     * Returns the current reference count.
     *
     * <p>For diagnostics and JFR instrumentation only. Do NOT use for control flow —
     * the value may change between reads in a concurrent context.
     *
     * @return current refCount ≥ 0; {@link Integer#MAX_VALUE} for the empty sentinel
     */
    int refCount();

    /**
     * Returns {@code true} if this payload is still alive (refCount &gt; 0).
     *
     * <p>Community: always {@code true} until GC.
     * Enterprise: {@code false} after the final {@link #close()} reduces refCount to 0.
     *
     * @return {@code true} if the payload may still be safely accessed
     */
    boolean isAlive();

    /**
     * Returns the empty {@code EventPayload} sentinel.
     *
     * <p>Use for events that carry no data (lifecycle signals, graph topology changes,
     * heartbeats). The empty payload has {@code length() == 0}, an empty
     * {@link MemorySegment}, and no backing memory. {@link #retain()} and
     * {@link #close()} are no-ops.
     *
     * @return the shared empty sentinel (never null)
     */
    static EventPayload empty() {
        return EmptyEventPayload.INSTANCE;
    }
}

