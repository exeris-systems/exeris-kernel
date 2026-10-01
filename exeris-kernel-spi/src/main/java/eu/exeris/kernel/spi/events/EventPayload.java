/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>Handler Contract</h2>
 * {@snippet lang="java" :
 * // Safe pattern — try-with-resources:
 * void handle(EventDescriptor descriptor, EventPayload payload) {
 *     try (payload) {
 *         MemorySegment bytes = payload.segment();
 *         // ... process bytes ...
 *     } // auto-close -> refCount--
 * }
 *
 * // Retain pattern — passing to another thread:
 * void handleOnAnotherThread(EventDescriptor descriptor, EventPayload payload) {
 *     payload.retain();   // refCount++ before fork
 *     scope.fork(() -> {
 *         try (payload) { // refCount-- when fork completes
 *             process(payload);
 *         }
 *         return null;
 *     });
 *     // do NOT call close() here — the fork owns it
 * }
 * }
 *
 * <h2>Sentinel Value</h2>
 * <p>Use {@link #empty()} for events that carry no data (lifecycle signals, graph edges).
 * The empty sentinel has infinite refCount and no backing memory — retain/close are no-ops.
 *
 * <p><b>Allocation:</b> allocates (the backing bytes once, when the payload is acquired — on the
 * heap, or from an off-heap pool, as the binding chooses); {@link #retain()} and {@link #close()}
 * themselves allocate nothing
 * <p><b>Thread confinement:</b> any thread — a payload may cross a thread boundary provided
 * {@link #retain()} precedes the hand-off and the receiving thread performs the matching
 * {@link #close()}; {@link #refCount()} may change under a concurrent reader
 * <p><b>Ownership:</b> whoever holds a reference owes exactly one {@link #close()} for it; the
 * final close returns the backing memory to its pool. Publishing transfers the caller's reference
 * to the {@link EventBus}, which then owns the broadcast fan-out
 *
 * @implSpec An implementation that owns backing memory returns it to its pool exactly once, on
 *           the transition from reference count 1 to 0, and rejects {@link #segment()} and
 *           {@link #retain()} thereafter with {@link IllegalStateException}. {@link #length()}
 *           stays readable after release so diagnostics can still attribute the payload. The
 *           {@link #empty()} sentinel is the documented exception: it owns nothing, never
 *           reaches zero, and its retain/close are no-ops.
 * @apiNote Prefer {@code try-with-resources}; on an off-heap binding a missed {@link #close()} is
 *          a slab-pool leak that shows up as exhaustion far from its cause.
 * @implNote The bindings that ship in this repository back the payload with a heap {@code byte[]}
 *           and count references with an {@link java.util.concurrent.atomic.AtomicInteger}, so a
 *           missed {@link #close()} costs no more than the collector's own timing. That is the
 *           cheap case rather than the general one: a binding that serves the payload from a pool
 *           has no collector behind it, and there the reference count is the only thing that
 *           returns the memory. The contract above is written in terms of the count, not of any
 *           backing store, so that one sentence describes both.
 * @since 0.5
 * @see EventDescriptor
 * @see EventBus
 * @see EventHandler
 */
public interface EventPayload extends AutoCloseable {

    /**
     * Exposes the payload bytes in place, as a Panama FFM {@link MemorySegment} over the backing
     * memory — no copy is made and none is implied.
     *
     * @return a read-only {@link MemorySegment} over the payload bytes, whose validity is bounded
     *         by this payload's lifetime, not by the caller's
     * @throws IllegalStateException if this payload has already been fully released
     * @apiNote The segment is only safe while {@link #isAlive()} holds. Reading it after the
     *          {@link #close()} that took the count to zero is undefined behaviour on an off-heap
     *          binding — the slab may already back a different event. Copy out anything that must
     *          outlive the handler.
     * @implNote Community backs the segment with {@code MemorySegment.ofArray(...)} over a heap
     *           array; Enterprise backs it with the off-heap slab at its raw address.
     */
    MemorySegment segment();

    /**
     * Reports how many bytes {@link #segment()} spans, without touching the backing memory —
     * so the answer survives release.
     *
     * @return payload length in bytes ({@code 0} for the {@link #empty()} sentinel)
     * @implSpec O(1), and readable even after the payload has been fully released, so that
     *           diagnostics and JFR can still size an event they arrived too late to read.
     */
    int length();

    /**
     * Claims one additional reference, so the backing memory outlives the current holder's
     * {@link #close()}.
     *
     * @throws IllegalStateException if the payload has already been fully released (refCount == 0)
     * @apiNote Call this <b>before</b> handing the payload to another thread or storing it beyond
     *          the current handler invocation; the new holder owes the matching {@link #close()}.
     *          Retaining after the hand-off is a race the reference count cannot save you from.
     * @implNote Community: a no-op — the GC manages lifetime. Enterprise: a VarHandle CAS
     *           increment on the slab's refCount field.
     */
    void retain();

    /**
     * Releases one reference, returning the backing memory to its pool once the last one is gone.
     *
     * @implSpec Called exactly once per {@link #retain()} and once for the initial acquire.
     *           Over-releasing is a programming error and SHOULD raise
     *           {@link IllegalStateException} in debug builds. An off-heap binding may emit a JFR
     *           event when the memory returns to its pool.
     * @implNote Community tolerates a repeated close (the heap array is simply left to the GC);
     *           Enterprise returns the slab slot on the 1-to-0 transition.
     */
    @Override
    void close();

    /**
     * Samples how many holders currently owe a {@link #close()} — an instantaneous count, not a
     * guarantee about the next instant.
     *
     * @return current refCount, never negative; {@link Integer#MAX_VALUE} for the
     *         {@link #empty()} sentinel, which is never released
     * @apiNote For diagnostics and JFR instrumentation only. Do not branch on it: another thread
     *          may retain or close between the read and the decision.
     */
    int refCount();

    /**
     * Reports whether the backing memory is still owned by at least one holder, and
     * {@link #segment()} therefore still safe to read.
     *
     * @return {@code true} while refCount &gt; 0
     * @implNote Community answers {@code true} for as long as the object exists; Enterprise
     *           answers {@code false} once the final {@link #close()} has returned the slab.
     */
    boolean isAlive();

    /**
     * Supplies the shared no-data payload, so a data-free event still satisfies the non-null
     * payload requirement of {@link EventBus#publish} without allocating or pooling anything.
     *
     * <p>Use it for events that carry no data (lifecycle signals, graph topology changes,
     * heartbeats).
     *
     * @return the immortal shared sentinel; never {@code null}. It reports {@code length() == 0},
     *         yields an empty {@link MemorySegment}, owns no backing memory, and treats
     *         {@link #retain()} and {@link #close()} as no-ops
     */
    static EventPayload empty() {
        return EmptyEventPayload.INSTANCE;
    }
}

