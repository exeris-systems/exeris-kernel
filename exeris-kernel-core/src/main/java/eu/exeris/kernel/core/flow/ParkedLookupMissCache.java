/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <p>Extracted from {@code CoreFlowRuntime} unchanged, behaviour for behaviour.
 */
final class ParkedLookupMissCache {

    private static final int MAX_ENTRIES = 256;

    private final Set<FlowKey> misses = ConcurrentHashMap.newKeySet();
    private final Deque<FlowKey> order = new ArrayDeque<>();
    private final Object lock = new Object();

    /* default */ boolean hasMiss(FlowKey key) {
        synchronized (lock) {
            return misses.contains(key);
        }
    }

    /* default */ void recordMiss(FlowKey key) {
        synchronized (lock) {
            if (misses.add(key)) {
                order.offerLast(key);
            }
            trimLocked();
        }
    }

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
