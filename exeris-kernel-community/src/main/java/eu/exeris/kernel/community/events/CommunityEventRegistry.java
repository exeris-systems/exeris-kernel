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

    @Override
    public EventTypeSpec resolve(String eventType) {
        return byName.get(eventType);
    }

    @Override
    public Set<String> registeredTypes() {
        return Set.copyOf(byName.keySet());
    }

    @Override
    public int size() {
        return byName.size();
    }

    /* default */ String nameOfOrdinal(int ordinal) {
        String name = byOrdinal.get(ordinal);
        return name != null ? name : "ordinal-" + ordinal;
    }
}
