/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.projection;

import eu.exeris.kernel.core.events.jfr.ProjectionAppliedEvent;
import eu.exeris.kernel.core.events.jfr.ProjectionHandlerFailureEvent;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import jdk.jfr.FlightRecorder;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core: In-memory event projection backed by an {@link EventBus} subscription.
 *
 * <h2>Purpose</h2>
 * <p>A {@code Projection} folds an immutable event stream into a single, atomically-updated
 * state value of type {@code S}. It subscribes to the {@link EventBus} on construction
 * and applies each received event via the user-supplied {@link ProjectionHandler}.
 *
 * <h2>State Update Protocol</h2>
 * <p>State transitions use {@link AtomicReference#updateAndGet} — lock-free, no
 * {@code synchronized}. The projection state is a single atomic reference updated
 * from within the bus dispatch thread (virtual thread). Callers read the current
 * state via {@link #state()} — O(1) volatile read.
 *
 * <h2>RAII Contract</h2>
 * <p>{@code Projection} is the sole owner of each {@link EventPayload} it receives
 * from the bus. It closes the payload via try-with-resources in {@code onEvent},
 * after the state fold completes (or fails). {@link ProjectionHandler#apply} MUST
 * NOT close the payload.
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #close()} to unsubscribe from the bus and stop receiving events.
 * After close, {@link #state()} still returns the last known state.
 *
 * @param <S> immutable projection state type
 * @since 0.5.0
 */
public final class Projection<S> implements AutoCloseable {

    private final String                 projectionName;
    private final ProjectionHandler<S>   handler;
    private final AtomicReference<S>     stateRef;
    private final EventBus               bus;
    private final SubscriptionToken      token;

    /**
     * Creates a projection that subscribes to the bus immediately.
     *
     * @param projectionName human-readable name for JFR telemetry
     * @param eventType      event type name to subscribe to
     * @param initialState   starting state (may be {@code null})
     * @param handler        the state-fold function
     * @param bus            the event bus to subscribe on
     * @param registry       used to validate that {@code eventType} is registered
     */
    public Projection(
            String projectionName,
            String eventType,
            S initialState,
            ProjectionHandler<S> handler,
            EventBus bus,
            EventRegistry registry) {
        this.projectionName = Objects.requireNonNull(projectionName, "projectionName");
        Objects.requireNonNull(eventType, "eventType");
        this.handler        = Objects.requireNonNull(handler,        "handler");
        this.bus            = Objects.requireNonNull(bus,            "bus");
        Objects.requireNonNull(registry, "registry");
        if (!registry.isRegistered(eventType)) {
            throw new IllegalArgumentException(
                    "Event type '" + eventType + "' is not registered in the EventRegistry. "
                    + "Register it before creating a Projection.");
        }
        this.stateRef = new AtomicReference<>(initialState);
        this.token    = bus.subscribe(eventType, this::onEvent);
    }

    /**
     * Returns the current projection state — O(1) volatile read.
     *
     * @return the latest folded state; may be {@code null} if no events have been processed yet
     */
    public S state() {
        return stateRef.get();
    }

    /**
     * Returns the human-readable projection name.
     *
     * @return non-null name
     */
    public String name() {
        return projectionName;
    }

    /**
     * Unsubscribes from the event bus. After this call, no more state updates occur.
     *
     * <p>The last computed state remains accessible via {@link #state()}.
     */
    @Override
    public void close() {
        bus.unsubscribe(token);
    }

    // =========================================================================
    // Internal handler
    // =========================================================================

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void onEvent(EventDescriptor descriptor, EventPayload payload) {
        try (payload) {
            stateRef.updateAndGet(current -> {
                try {
                    return handler.apply(current, descriptor, payload);
                } catch (RuntimeException handlerException) {
                    emitProjectionFailure(descriptor, handlerException);
                    throw handlerException;
                }
            });
            emitJfr(descriptor);
        }
    }

    private void emitProjectionFailure(EventDescriptor descriptor, RuntimeException handlerException) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ProjectionHandlerFailureEvent evt = new ProjectionHandlerFailureEvent();
        if (evt.isEnabled()) {
            evt.projectionName   = projectionName;
            evt.eventTypeOrdinal = descriptor.eventTypeOrdinal();
            evt.exceptionType    = handlerException.getClass().getSimpleName();
            evt.commit();
        }
    }

    private void emitJfr(EventDescriptor descriptor) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ProjectionAppliedEvent evt = new ProjectionAppliedEvent();
        if (evt.isEnabled()) {
            evt.projectionName  = projectionName;
            evt.eventTypeOrdinal = descriptor.eventTypeOrdinal();
            evt.streamIdHigh    = descriptor.streamIdHigh();
            evt.streamIdLow     = descriptor.streamIdLow();
            evt.commit();
        }
    }
}
