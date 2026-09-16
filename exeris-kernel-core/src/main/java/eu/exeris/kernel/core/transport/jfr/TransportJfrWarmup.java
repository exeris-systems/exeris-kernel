/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import java.lang.invoke.MethodHandles;

/**
 * Initialises transport JFR event classes on the thread that starts an engine, so that no virtual
 * thread pays for their class initialisation on the stream path.
 *
 * <h2>Why an event class must not initialise on a stream's virtual thread</h2>
 * <p>A {@link jdk.jfr.Event} subclass is registered with the JFR metadata repository from its own
 * static initialiser — the JVM instruments the class and its {@code <clinit>} calls
 * {@link jdk.jfr.FlightRecorder#register(Class)}. That work is not free: it loads the class from
 * the jar (manifest and zip entry lookups included) and enters JFR's repository.
 *
 * <p>A virtual thread running a {@code <clinit>} <strong>cannot unmount</strong> — the JVM reports
 * the pin as {@code "VM call to <class>.<clinit> on stack"}. Every other virtual thread that
 * reaches the same class in that window blocks in {@code Object.wait} inside class initialisation,
 * which is also pinned ({@code "Waited for initialization of <class> by another thread"}): JEP 491
 * unpinned {@code synchronized} and {@code Object.wait}, but not class-initialisation waits. Where
 * carriers are scarce the two feed each other — the waiters hold the carriers the initialiser needs.
 *
 * <p>An engine's first emit site is therefore the worst possible place to initialise these classes:
 * it is reached once per engine, on a virtual thread, at the moment the engine is busiest.
 *
 * <h2>Measured</h2>
 * <p>With {@code StreamLifecycleEvent} left to initialise on the first completing stream's virtual
 * thread, under the two-carrier model
 * ({@code -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2})
 * and a recording started with {@code jdk.VirtualThreadPinned#threshold=0ms}: 0.4–1 ms per pin on an
 * idle 12-core host, 15–16 ms on the same host under CPU pressure, and past the 20 ms fence of
 * {@code CommunityClientIngressCarrierPinningTest} on a constrained runner. The pins disappear once
 * the classes are initialised here instead.
 *
 * <h2>Contract</h2>
 * <p>Idempotent and cheap after the first call: initialising an already-initialised class is a
 * state check. It is a warm-up, not a registration protocol — an event class that is never warmed
 * still works, it simply initialises at its first {@code emit(...)}, wherever that lands.
 *
 * @since 0.12
 */
public final class TransportJfrWarmup {

    /**
     * Every JFR event class this module emits from the transport path.
     *
     * <p>The whole set, not the subset a reading of the current call sites says a virtual thread can
     * reach: which thread emits which event is not enforced anywhere, so that subset would drift
     * silently. The set is small and closed, and initialising a class the engine never emits costs
     * one class load at start-up.
     */
    private static final Class<?>[] CORE_TRANSPORT_EVENTS = {
            ConnectionEstablishedEvent.class,
            PaqsHandlerFailureEvent.class,
            StreamAcceptedEvent.class,
            StreamLifecycleEvent.class,
            StreamShedEvent.class,
            TransportIngressQueueDepthEvent.class,
            TransportQueueBackpressureAlertEvent.class,
    };

    private TransportJfrWarmup() {
        // Utility class — no instances.
    }

    /**
     * Initialises the Core transport JFR event classes.
     *
     * <p>Call from engine construction or {@code start()}, on the starting thread, before the first
     * stream exists.
     */
    public static void ensureRegistered() {
        ensureRegistered(MethodHandles.lookup(), CORE_TRANSPORT_EVENTS);
    }

    /**
     * Initialises a driver's own JFR event classes, the same way and for the same reason.
     *
     * <p>A driver's event classes are usually package-private, so it passes its own
     * {@link MethodHandles#lookup() lookup} rather than relying on one taken here.
     *
     * @param lookup       a lookup that can see {@code eventClasses}; must not be {@code null}
     * @param eventClasses the event classes to initialise; must not be {@code null}
     * @throws IllegalArgumentException if {@code lookup} cannot see one of {@code eventClasses} —
     *                                  a wiring defect, raised at start-up rather than left to
     *                                  surface as a pin on a stream's virtual thread
     */
    public static void ensureRegistered(MethodHandles.Lookup lookup, Class<?>... eventClasses) {
        for (Class<?> eventClass : eventClasses) {
            try {
                lookup.ensureInitialized(eventClass);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(
                        "Lookup cannot initialise JFR event class " + eventClass.getName(), e);
            }
        }
    }
}
