/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.util.UUID;

/**
 * TCK: JFR Zero-Allocation monitor for the EventBus publish hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code bus.publish(pre-built descriptor, EventPayload.empty())} — the most
 * critical path in the L3 event bus. The descriptor and payload are pre-built
 * before the recording starts; only routing and dispatch overhead is measured.
 *
 * <h2>Enterprise Contract</h2>
 * <p>Enterprise implementations route via a lock-free off-heap ring buffer
 * (single CAS write). Zero {@code eu.exeris.*} heap objects per publish.
 *
 * <h2>Community Contract</h2>
 * <p>Community routes via a {@code ConcurrentHashMap} dispatch table and
 * a {@code StructuredTaskScope}. Bounded allocation (handler wrapper allocation
 * is acceptable; {@code new EventDescriptor} per-call is NOT).
 *
 * @since 0.5
 */
public abstract class EventBusZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    private static final String EVENT_TYPE    = "ZeroAllocTestEvent";
    private static final int    EVENT_ORDINAL = 99;

    /**
     * Creates a fully configured, not-yet-started {@link EventEngine}.
     *
     * @return a fresh {@link EventEngine}
     */
    protected abstract EventEngine createEngine();

    private EventEngine     engine;
    private EventDescriptor hotDescriptor;

    /**
     * Creates the contract; subclasses supply the {@link EventEngine} under test via
     * {@link #createEngine()}.
     */
    public EventBusZeroAllocTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override protected String subsystemName()     { return "EventBus"; }
    @Override protected String hotPathDescription() {
        return "EventBus.publish(pre-built descriptor, EventPayload.empty())";
    }
    @Override protected int warmupIterations()      { return 1_000; }
    @Override protected int hotPathIterations()     { return 100_000; }

    /**
     * Enterprise: 0 allocations per publish (ring-buffer CAS).
     * Community: ≤ 4 (scope wrapper, subtask, handler invocation, optional envelope).
     */
    @Override protected int maxExerisAllocationsPerIteration() { return 4; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.of(EVENT_TYPE, EVENT_ORDINAL));

        // Register a no-op handler that correctly closes the payload
        engine.bus().subscribe(EVENT_TYPE, (descriptor, payload) -> {
            try (payload) {  // NOPMD EmptyControlStatement - closing the payload IS the contract
                // intentionally empty — we measure routing, not handler work
            }
        });
        engine.start();

        // Pre-build the hot descriptor ONCE — all-primitive, Valhalla-ready record.
        // Reused across ALL iterations: zero allocation from this point.
        UUID id = UUID.nameUUIDFromBytes("zero-alloc-event".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hotDescriptor = new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                EVENT_ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }

    @Override
    protected void runSingleIteration() {
        // EventPayload.empty() is the shared sentinel — no allocation.
        // The bus MUST NOT create a new object per publish on the Enterprise path.
        engine.bus().publish(hotDescriptor, EventPayload.empty());
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) {
            engine.close();
        }
    }
}

