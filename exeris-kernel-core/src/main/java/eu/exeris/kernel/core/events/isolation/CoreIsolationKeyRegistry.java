/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.isolation;

import eu.exeris.kernel.spi.events.IsolationKeyRegistry;
import eu.exeris.kernel.spi.events.IsolationKeySpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Core: heap-backed, thread-safe {@link IsolationKeyRegistry} shared by every event binding.
 *
 * <h2>Implementation</h2>
 * <p>Two {@link ConcurrentHashMap}s: isolation key to spec, and ordinal to key. A key and an
 * ordinal each identify at most one registration, so a conflict on either side is refused, and
 * an identical re-registration is a no-op. Registration is an administrative operation and may
 * happen at any time; lookups are O(1) and allocate nothing.
 *
 * @since 0.13
 */
public final class CoreIsolationKeyRegistry implements IsolationKeyRegistry {

    private final ConcurrentMap<String, IsolationKeySpec> byKey     = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String>          byOrdinal = new ConcurrentHashMap<>();

    /** Creates an empty registry; every key is registered by the application. */
    public CoreIsolationKeyRegistry() {
        // no keys until the application registers them
    }

    /**
     * Inserts {@code spec} into both maps, or validates it against an existing entry when either
     * side is already taken. On a conflict, the ordinal insertion this call may have made is rolled
     * back before throwing, so a rejected registration leaves no partial entry behind.
     *
     * @param spec the isolation key specification (non-null)
     * @throws EventRegistryException EX-EVENT-6012 if the ordinal is already claimed by a different
     *         key, or the key is already registered under a different ordinal
     */
    @Override
    public void register(IsolationKeySpec spec) {
        Objects.requireNonNull(spec, "spec");

        String existingKey = byOrdinal.putIfAbsent(spec.ordinal(), spec.isolationKey());
        if (existingKey != null && !existingKey.equals(spec.isolationKey())) {
            throw EventRegistryException.isolationKeyConflict(spec.isolationKey(), spec.ordinal());
        }
        boolean insertedOrdinal = existingKey == null;

        IsolationKeySpec existing = byKey.putIfAbsent(spec.isolationKey(), spec);
        if (existing != null && !existing.equals(spec)) {
            if (insertedOrdinal) {
                byOrdinal.remove(spec.ordinal(), spec.isolationKey());
            }
            throw EventRegistryException.isolationKeyConflict(spec.isolationKey(), spec.ordinal());
        }
    }

    /**
     * Looks up the registered spec for a key in the backing map, O(1).
     *
     * @param isolationKey the isolation key (non-null)
     * @return the registered spec, or {@code null} if the key was never registered
     */
    @Override
    public IsolationKeySpec resolve(String isolationKey) {
        return byKey.get(isolationKey);
    }

    /**
     * Returns an immutable copy of the currently registered keys.
     *
     * @return immutable set of registered isolation keys
     */
    @Override
    public Set<String> registeredKeys() {
        return Set.copyOf(byKey.keySet());
    }

    /**
     * Returns the number of registered isolation keys.
     *
     * @return count &ge; 0
     */
    @Override
    public int size() {
        return byKey.size();
    }
}
