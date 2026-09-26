/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import java.util.Set;

/**
 * SPI: Event type registry.
 *
 * <h2>Type System</h2>
 * <p>The registry maps event type names (e.g. {@code "UserCreated"}) to integer ordinals.
 * Ordinals enable O(1) routing in the {@link EventBus} and {@link EventLoop} —
 * no {@link String} comparison in the hot path.
 *
 * <h2>Ordinal Assignment</h2>
 * <p>Ordinals are <b>caller-defined</b>: they are supplied by the caller via
 * {@link EventTypeSpec} at registration time. The registry is the arbiter of uniqueness, not the
 * source of the numbers.
 *
 * @implSpec An implementation does <b>not</b> assign ordinals sequentially — it validates that
 *           the supplied ordinal is unique within the registry and raises
 *           {@link EventRegistryException} on conflict.
 * @apiNote Register every type before subscribing to it or publishing it — the in-memory bus
 *          rejects a subscription to an unregistered type, and ordinal routing has nothing to
 *          route on without an entry.
 * @implNote The Community binding is a thread-safe heap map that validates uniqueness on each
 *           {@link #register} call and accepts registrations at any time. A native binding is a
 *           fixed-capacity off-heap map whose routing table is built once during
 *           {@link EventEngine#start()} and immutable afterwards, so every type must be
 *           registered before that call.
 * @since 0.5
 * @see EventTypeSpec
 */
public interface EventRegistry {

    /**
     * Admits an event type into the routing table, claiming its ordinal for the life of the
     * registry.
     *
     * @param spec the event type specification (non-null)
     * @throws EventRegistryException {@code EX-EVENT-6003} if the type name is already registered
     *         with different settings, or its ordinal is already claimed; {@code rawArgs} carry
     *         {@code [String eventType, int ordinal]}
     * @implSpec Idempotent for an identical re-registration — the same type name with the same
     *           settings has no effect and does not raise. Only a genuine disagreement conflicts.
     * @apiNote A native binding requires every type to be registered before
     *          {@link EventEngine#start()}.
     */
    void register(EventTypeSpec spec);

    /**
     * Looks up the full registration record for a type name — the shape the publish path needs
     * when it must know more than the ordinal (durability, ordering, topic override).
     *
     * @param eventType the event type name (non-null)
     * @return the registered spec, or {@code null} if the type was never registered
     * @implSpec O(1) — a hash lookup, not a scan.
     */
    EventTypeSpec resolve(String eventType);

    /**
     * Resolves a type name to the integer the dispatch path routes on, without materialising the
     * registration record.
     *
     * @param eventType the event type name (non-null)
     * @return the ordinal claimed at registration, or {@code -1} if the type is not registered
     * @apiNote This is the hot-path lookup: it avoids handling an {@link EventTypeSpec} at all.
     */
    default int ordinalOf(String eventType) {
        EventTypeSpec spec = resolve(eventType);
        return spec != null ? spec.ordinal() : -1;
    }

    /**
     * Enumerates the type names currently registered, as a snapshot that does not track later
     * registrations.
     *
     * @return an immutable set of the registered type names; empty when nothing is registered
     * @apiNote May allocate a fresh {@link Set} per call — for diagnostics, startup checks and
     *          subscription refresh, not for a dispatch path.
     */
    Set<String> registeredTypes();

    /**
     * Answers whether a type name has a registration, without materialising it.
     *
     * @param eventType the event type name (non-null)
     * @return {@code true} when {@link #resolve} would return a spec for this name
     */
    default boolean isRegistered(String eventType) {
        return resolve(eventType) != null;
    }

    /**
     * Counts the registrations held, the cardinality of {@link #registeredTypes()} without
     * building it.
     *
     * @return the number of registered event types; never negative
     */
    int size();
}

