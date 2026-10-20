/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.events.IsolationKeySpec;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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

    /**
     * The same hot path with a tenant context bound and a descriptor already stamped with that
     * tenant's ordinal (ADR-101). The stamp has nothing to change, so it must hand the descriptor
     * back unchanged, and the subscriber receives that same instance.
     *
     * <p>The identity check is what this class asserts about allocation. A sampled allocation profile
     * sees one small descriptor per publish only rarely, next to the bytes each publish allocates for
     * dispatch, so it cannot tell a stamp that allocates from one that does not.
     *
     * <p>Inherited by every binding, because it is a member of this contract.
     */
    @Nested
    @DisplayName("Isolation-stamped publish — the stamp allocates no descriptor (ADR-101)")
    class IsolationStampedPublish extends AbstractSubsystemZeroAllocTck {

        private static final String TENANT         = "zero-alloc-tenant";
        private static final int    TENANT_ORDINAL = 101;

        private EventEngine         isolatedEngine;
        private ImmutableStorageContext context;
        private EventDescriptor     stampedDescriptor;
        private Runnable            publishRunnable;
        private final AtomicReference<EventDescriptor> delivered = new AtomicReference<>();

        @Override protected String subsystemName()     { return "EventBus"; }
        @Override protected String hotPathDescription() {
            return "EventBus.publish(pre-stamped descriptor, EventPayload.empty()) under a bound isolation key";
        }
        @Override protected int warmupIterations()      { return 1_000; }
        @Override protected int hotPathIterations()     { return 100_000; }

        /** Same bound as the enclosing contract: the budget is not what this case tightens. */
        @Override protected int maxExerisAllocationsPerIteration() { return 4; }

        @Override
        protected void bootstrapSubsystem() {
            isolatedEngine = createEngine();
            isolatedEngine.registry().register(EventTypeSpec.of(EVENT_TYPE, EVENT_ORDINAL));
            isolatedEngine.isolationRegistry().register(IsolationKeySpec.of(TENANT, TENANT_ORDINAL));
            context = ImmutableStorageContext.shared(TENANT);

            // A scoped subscriber so the stamped descriptor is routed to a handler, not dropped at match 0.
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).run(() ->
                    isolatedEngine.bus().subscribeScoped(EVENT_TYPE, (descriptor, payload) -> {
                        try (payload) {
                            delivered.set(descriptor);
                        }
                    }));
            isolatedEngine.start();

            UUID id = UUID.nameUUIDFromBytes("zero-alloc-stamped-event".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stampedDescriptor = EventDescriptor.of(
                    id.getMostSignificantBits(), id.getLeastSignificantBits(),
                    0L, 0L,
                    EVENT_ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis(), TENANT_ORDINAL);
            publishRunnable = () -> isolatedEngine.bus().publish(stampedDescriptor, EventPayload.empty());
        }

        @Override
        protected void runSingleIteration() {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).run(publishRunnable);
        }

        @Override
        protected void tearDownSubsystem() {
            if (isolatedEngine != null) {
                isolatedEngine.close();
            }
        }

        /**
         * A pre-stamped descriptor is delivered as the instance that was published: the stamp returns it
         * unchanged rather than building an equal copy. Run in-thread, so the delivery has happened by
         * the time the call returns.
         */
        @Test
        @DisplayName("a pre-stamped descriptor is delivered as the same instance")
        void preStampedDescriptorIsDeliveredAsTheSameInstance() throws Exception {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).call(() -> {
                isolatedEngine.bus().publishAndAwait(stampedDescriptor, EventPayload.empty());
                return null;
            });

            assertThat(delivered.get())
                    .as("the stamp MUST return the descriptor it was given, so the subscriber receives that instance")
                    .isSameAs(stampedDescriptor);
        }
    }
}

