/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * {@link EventTypeSpec} at registration time. Implementations do <b>not</b> assign ordinals
 * sequentially — they <b>validate</b> that the supplied ordinal is unique within the registry
 * and throw {@link EventRegistryException} on conflict.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Standard</b>: Thread-safe in-memory map on the heap.
 *       Validates ordinal uniqueness on each {@link #register} call.</li>
 *   <li><b>High-performance native</b>: Off-heap map with fixed capacity.
 *       All types must be registered before {@link EventEngine#start()} —
 *       the routing table is built once during startup and immutable thereafter.</li>
 * </ul>
 *
 * <h2>Registration Order</h2>
 * <p>Enterprise implementations require all types to be registered before
 * {@link EventEngine#start()} — the routing table is built once during startup.
 * Community implementations allow registration at any time.
 *
 * @since 0.5.0
 * @see EventTypeSpec
 */
public interface EventRegistry {

    /**
     * Registers an event type specification.
     *
     * <p>This operation is idempotent — registering the same type name twice with
     * identical settings has no effect. Re-registering with different settings
     * throws {@link EventRegistryException}.
     *
     * <p>Native implementations require all types to be registered before
     * {@link EventEngine#start()}.
     *
     * @param spec the event type specification (non-null)
     * @throws EventRegistryException if the type is already registered with different settings
     */
    void register(EventTypeSpec spec);

    /**
     * Resolves the {@link EventTypeSpec} for the given type name.
     *
     * <p>Returns {@code null} if the type has not been registered.
     * This method is O(1) — hash lookup.
     *
     * @param eventType the event type name (non-null)
     * @return the registered spec, or {@code null} if not found
     */
    EventTypeSpec resolve(String eventType);

    /**
     * Returns the ordinal assigned to the given event type name.
     *
     * <p>Returns {@code -1} if the type is not registered.
     * Use this for hot-path routing to avoid creating {@link EventTypeSpec} objects.
     *
     * @param eventType the event type name (non-null)
     * @return the assigned ordinal, or {@code -1} if not registered
     */
    default int ordinalOf(String eventType) {
        EventTypeSpec spec = resolve(eventType);
        return spec != null ? spec.ordinal() : -1;
    }

    /**
     * Returns an immutable snapshot of all registered event type names.
     *
     * <p>This method may allocate a new {@link Set} — do not call it in hot paths.
     *
     * @return immutable set of registered type names
     */
    Set<String> registeredTypes();

    /**
     * Returns {@code true} if the given type is registered.
     *
     * @param eventType the event type name (non-null)
     * @return {@code true} if registered
     */
    default boolean isRegistered(String eventType) {
        return resolve(eventType) != null;
    }

    /**
     * Returns the total number of registered event types.
     *
     * @return count ≥ 0
     */
    int size();
}

