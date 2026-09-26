/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Community: heap-backed, thread-safe {@link EventRegistry}.
 *
 * <h2>Implementation</h2>
 * <p>Uses a {@link ConcurrentHashMap} keyed by event type name. Ordinal uniqueness is
 * validated on every {@link #register} call — duplicate ordinal with a different name throws
 * {@link EventRegistryException}. Idempotent re-registration with identical settings is
 * allowed (no-op).
 *
 * <h2>Performance</h2>
 * <p>O(1) {@link #resolve} and {@link #ordinalOf} for the hot dispatch path.
 * Registration is an administrative operation, allowed at any time.
 *
 * @since 0.5
 */
final class CommunityEventRegistry implements EventRegistry {

    private final ConcurrentMap<String, EventTypeSpec> byName    = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String>       byOrdinal = new ConcurrentHashMap<>();

    /**
     * Inserts {@code spec} into both the name- and ordinal-keyed maps, or validates it against
     * an existing entry when either key is already taken.
     *
     * <p>Registration may happen at any time — this registry places no ordering requirement
     * relative to {@link eu.exeris.kernel.spi.events.EventEngine#start()}. On a name/ordinal
     * conflict, the ordinal insertion this call may have already made is rolled back before
     * throwing, so a rejected registration leaves no partial entry behind.
     *
     * @param spec the event type specification (non-null)
     * @throws EventRegistryException EX-EVENT-6003 if {@code spec.ordinal()} is already bound
     *         to a different name, or {@code spec.name()} is already bound to a
     *         non-equal {@link EventTypeSpec}
     */
    @Override
    public void register(EventTypeSpec spec) {
        String existingName = byOrdinal.putIfAbsent(spec.ordinal(), spec.name());
        if (existingName != null && !existingName.equals(spec.name())) {
            throw EventRegistryException.duplicateConflict(spec.name(), spec.ordinal());
        }
        boolean insertedOrdinal = existingName == null;

        EventTypeSpec existing = byName.putIfAbsent(spec.name(), spec);
        if (existing != null && !existing.equals(spec)) {
            if (insertedOrdinal) {
                byOrdinal.remove(spec.ordinal(), spec.name());
            }
            throw EventRegistryException.duplicateConflict(spec.name(), spec.ordinal());
        }
    }

    /**
     * Looks up the registered spec by name in the backing {@link ConcurrentHashMap} — O(1).
     *
     * @param eventType the event type name (non-null)
     * @return the registered spec, or {@code null} if not found
     */
    @Override
    public EventTypeSpec resolve(String eventType) {
        return byName.get(eventType);
    }

    /**
     * Returns an immutable copy of the currently registered type names.
     *
     * @return immutable set of registered type names
     */
    @Override
    public Set<String> registeredTypes() {
        return Set.copyOf(byName.keySet());
    }

    /**
     * Returns the number of entries in the name-keyed map.
     *
     * @return count &ge; 0
     */
    @Override
    public int size() {
        return byName.size();
    }

    /* default */ String nameOfOrdinal(int ordinal) {
        String name = byOrdinal.get(ordinal);
        return name != null ? name : "ordinal-" + ordinal;
    }
}
