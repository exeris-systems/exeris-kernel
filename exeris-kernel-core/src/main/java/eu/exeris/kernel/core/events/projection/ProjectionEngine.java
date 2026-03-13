/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.projection;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core: Registry and lifecycle manager for event-sourced {@link Projection} instances.
 *
 * <h2>Responsibility</h2>
 * <p>The {@code ProjectionEngine} is the infrastructure layer for local state projections.
 * Application code registers named projections here; the engine wires each projection to
 * the {@link EventBus} and manages its subscription token lifecycle.
 *
 * <h2>Thread Safety</h2>
 * <p>Projections are stored in a {@code ConcurrentHashMap} — concurrent registration and
 * state reads are safe. Each {@link Projection}'s state is updated via
 * {@code AtomicReference.updateAndGet} inside the handler — lock-free.
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #close()} to unsubscribe all registered projections from the bus.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.CloseResource")
public final class ProjectionEngine implements AutoCloseable {

    private final EventBus      bus;
    private final EventRegistry registry;

    private final Map<String, Projection<?>> projections = new ConcurrentHashMap<>();

    /**
     * Creates the engine.
     *
     * @param bus      the active event bus (non-null)
     * @param registry the active event type registry (non-null)
     */
    public ProjectionEngine(EventBus bus, EventRegistry registry) {
        this.bus      = Objects.requireNonNull(bus,      "bus");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Registers a new named projection.
     *
     * <p>The projection is subscribed to the bus immediately. If a projection with
     * the same name is already registered, this is a no-op (idempotent).
     *
     * @param <S>            projection state type
     * @param name           unique projection name (used as lookup key)
     * @param eventType      the event type to subscribe to
     * @param initialState   starting state value (may be {@code null})
     * @param handler        the fold function
     * @return the registered (or pre-existing) {@link Projection}
     */
    @SuppressWarnings("unchecked")
    public <S> Projection<S> register(
            String name,
            String eventType,
            S initialState,
            ProjectionHandler<S> handler) {
        Objects.requireNonNull(name,      "name");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler,   "handler");

        return (Projection<S>) projections.computeIfAbsent(name,
                _ -> new Projection<>(name, eventType, initialState, handler, bus, registry));
    }

    /**
     * Retrieves a previously registered projection by name.
     *
     * @param <S>  projection state type
     * @param name the projection name
     * @return the {@link Projection}, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    public <S> Projection<S> get(String name) {
        return (Projection<S>) projections.get(name);
    }

    /**
     * Unregisters a projection by name, unsubscribing it from the bus.
     *
     * @param name the projection name
     */
    public void unregister(String name) {
        Projection<?> projection = projections.remove(name);
        if (projection != null) {
            projection.close();
        }
    }

    /**
     * Returns the number of active projections.
     *
     * @return count ≥ 0
     */
    public int size() {
        return projections.size();
    }

    /**
     * Unsubscribes all projections from the bus and clears the registry.
     */
    @Override
    public void close() {
        for (Projection<?> projection : projections.values()) {
            projection.close();
        }
        projections.clear();
    }
}
