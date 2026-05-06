/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Heap-backed thread-safe {@link EventRegistry} implementation used by the Kafka driver.
 *
 * <p>Functionally identical to {@code CommunityEventRegistry} (the package-private
 * Community heap registry); duplicated here so the Kafka module does not depend on a
 * package-private Community internal. {@code AbstractEventRegistryTck} (Sprint 5a)
 * defines the obligation; this class satisfies it the same way the Community one does.
 *
 * @since 0.7.0
 */
final class KafkaEventRegistry implements EventRegistry {

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

    /** Reverse lookup used by the Kafka publish path to compute topic names from ordinals. */
    /* default */ String nameOfOrdinal(int ordinal) {
        return byOrdinal.get(ordinal);
    }
}
