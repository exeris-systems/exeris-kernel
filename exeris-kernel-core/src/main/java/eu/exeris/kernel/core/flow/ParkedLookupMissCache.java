/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded negative cache for parked-instance lookups that missed the durable store.
 *
 * <p>Keeps choreography polling from re-probing the store for a key it has already failed to find,
 * without ever masking a genuine PARKED instance: every park, wake, complete, recompile and engine
 * restart clears the entry for the key it touches. FIFO eviction past
 * {@value #MAX_ENTRIES} bounds the memory this can hold under an unbounded stream of unknown keys.
 *
 * @apiNote Every method is safe to call from any thread; a single internal lock serializes the
 *          miss set and its FIFO eviction order.
 */
final class ParkedLookupMissCache {

    private static final int MAX_ENTRIES = 256;

    private final Set<FlowKey> misses = ConcurrentHashMap.newKeySet();
    private final Deque<FlowKey> order = new ArrayDeque<>();
    private final Object lock = new Object();

    /**
     * Returns whether {@code key} is currently recorded as a miss.
     *
     * @param key the lookup key to check
     * @return {@code true} if {@code key} was recorded by {@link #recordMiss} and not since cleared
     */
    /* default */ boolean hasMiss(FlowKey key) {
        synchronized (lock) {
            return misses.contains(key);
        }
    }

    /**
     * Records {@code key} as a miss, evicting the oldest recorded miss first if this pushes the
     * cache past {@value #MAX_ENTRIES} entries.
     *
     * @param key the lookup key that missed the durable store
     */
    /* default */ void recordMiss(FlowKey key) {
        synchronized (lock) {
            if (misses.add(key)) {
                order.offerLast(key);
            }
            trimLocked();
        }
    }

    /**
     * Removes any recorded miss for {@code key}; a no-op if none is recorded.
     *
     * @param key the lookup key whose entry, if any, should be cleared
     */
    /* default */ void clearMiss(FlowKey key) {
        synchronized (lock) {
            if (misses.remove(key)) {
                boolean removed;
                do {
                    removed = order.removeFirstOccurrence(key);
                } while (removed);
            }
        }
    }

    /** Discards every recorded miss. */
    /* default */ void clearAll() {
        synchronized (lock) {
            misses.clear();
            order.clear();
        }
    }

    private void trimLocked() {
        while (order.size() > MAX_ENTRIES) {
            FlowKey oldest = order.pollFirst();
            if (oldest == null) {
                break;
            }
            misses.remove(oldest);
        }
    }
}
