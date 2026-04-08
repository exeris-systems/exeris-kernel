/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.context;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.exceptions.security.StorageContextMissingException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowProvider;
import eu.exeris.kernel.spi.flow.IdempotencyGuard;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportProvider;

import java.util.List;
import java.util.Optional;

/**
 * Central {@link ScopedValue} slots for all SPI providers resolved during bootstrap.
 *
 * <h2>Zero Static Singletons (The Wall)</h2>
 * <p>This class replaces the legacy pattern of {@code static} fields on {@code MemoryManager},
 * {@code TelemetryRouter}, etc. There are no mutable singletons, no double-checked locking,
 * and no {@code ThreadLocal} caches. Every subsystem reads its provider from the scoped slot
 * that was bound by the kernel bootstrapper.
 *
 * <h2>Context Propagation Model (JEP 506)</h2>
 * <p>{@code ScopedValue} slots are inherited by every {@link Thread#startVirtualThread(Runnable) virtual thread}
 * spawned within the binding scope. This means a single {@code ScopedValue.where(...).run(...)}
 * in {@code KernelBootstrap} covers the entire lifetime of the kernel — thousands of virtual
 * threads all read the same provider instances with zero synchronisation overhead.
 *
 * <h2>Binding (bootstrap side)</h2>
 * <pre>{@code
 * ScopedValue
 *     .where(KernelProviders.CURRENT_CONFIG,   configProvider)   // L0 — bound first
 *     .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
 *     .where(KernelProviders.MEMORY_PROVIDER,  provider)
 *     .where(KernelProviders.CARRIER_INDEX, 0)
 *     .run(kernel::startSubsystems);
 * }</pre>
 *
 * <h2>Reading (subsystem / handler side)</h2>
 * <pre>{@code
 * LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
 *     .allocate(AllocationHint.MEDIUM);
 * }</pre>
 *
 * <h2>CarrierLoop affinity</h2>
 * <p>{@link #CARRIER_INDEX} is re-bound per carrier loop iteration so that the
 * carrier-affine slab pool selection in {@link MemoryAllocator#allocateCarrierSlab(int)}
 * requires no argument threading — the index flows via {@code ScopedValue}.
 *
 * @since 0.5.0
 * @see <a href="../../../../../../docs/subsystems/memory.md">memory.md</a>
 */
@SuppressWarnings("PMD.TooManyMethods") // Central ScopedValue slot registry — splitting would violate the SPI Wall.
public final class KernelProviders {

    // =========================================================================
    // Config Slot (L0 Foundation — bound before Memory)
    // =========================================================================

    /**
     * The active {@link ConfigProvider} (bound once during L0 bootstrap, before any other slot).
     *
     * <p>Bound by the kernel bootstrapper in {@code exeris-kernel-core} immediately
     * after {@code ServiceLoader} selects the highest-priority {@link ConfigProvider}.
     * All virtual threads spawned within the kernel scope inherit this slot
     * automatically — zero constructor injection needed.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * ConfigProvider.KernelSettings settings =
     *     KernelProviders.CURRENT_CONFIG.get().kernelSettings().get();
     * int port = settings.network().port();
     * }</pre>
     *
     * <h2>Convenience accessor</h2>
     * <pre>{@code
     * ConfigProvider cfg = KernelProviders.config();
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<ConfigProvider> CURRENT_CONFIG = ScopedValue.newInstance();

    /**
     * The active {@link MemoryProvider} factory (bound once during bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect or reconfigure
     * the provider. Application code should use {@link #MEMORY_ALLOCATOR} directly.
     */
    public static final ScopedValue<MemoryProvider> MEMORY_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link MemoryAllocator} (created from {@link #MEMORY_PROVIDER}).
     *
     * <p>This is the primary slot for all allocation calls. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * try (LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
     *         .allocate(AllocationHint.SMALL)) {
     *     // zero-copy processing
     * }
     * }</pre>
     */
    public static final ScopedValue<MemoryAllocator> MEMORY_ALLOCATOR = ScopedValue.newInstance();

    /**
     * Zero-based index of the current carrier thread within the CarrierLoop pool.
     *
     * <p>Re-bound by the CarrierLoop dispatcher on every iteration so that
     * {@link MemoryAllocator#allocateCarrierSlab(int)} can select the NUMA-local
     * slab pool without requiring an explicit argument at every call site.
     *
     * <p>Defaults to {@code 0} if the scope was not set by a CarrierLoop
     * (e.g., during unit tests). Implementations of {@link MemoryAllocator}
     * MUST handle index {@code 0} as a valid, always-present pool.
     */
    public static final ScopedValue<Integer> CARRIER_INDEX = ScopedValue.newInstance();

    /**
     * The active {@link KernelCryptoProvider} (bound once during bootstrap).
     *
     * <p>Transport subsystems read this slot to create {@link eu.exeris.kernel.spi.crypto.TlsEngine}
     * instances. The bootstrapper also checks {@link KernelCryptoProvider#supportsQuic()} here
     * to decide whether to activate QUIC transport.
     */
    public static final ScopedValue<KernelCryptoProvider> CRYPTO_PROVIDER = ScopedValue.newInstance();

    /**
     * The active {@link TelemetryProvider} (bound once during bootstrap).
     *
     * <p>Subsystems use this slot to emit {@link eu.exeris.kernel.spi.telemetry.KernelEvent} objects
     * to the active sink chain without holding a direct reference to any sink implementation.
     */
    public static final ScopedValue<TelemetryProvider> TELEMETRY_PROVIDER = ScopedValue.newInstance();

    /**
     * The resolved, ready-to-use list of {@link TelemetrySink} instances (bound once during bootstrap).
     *
     * <h2>Why sinks and not the provider</h2>
     * <p>The provider is a factory — it has already done its job once {@code createSinks()} returned.
     * Subsystems (Transport, Persistence, Crypto) should never call {@code createSinks()} again.
     * Binding the pre-built list means zero indirection and zero object creation on the emit path.
     * Use an explicit {@code for} loop on the hot path — a lambda passed to {@code forEach} may
     * allocate a new instance per call on some JVM builds:
     * <pre>{@code
     * for (TelemetrySink sink : KernelProviders.TELEMETRY_SINKS.get()) {
     *     sink.emit(event);
     * }
     * }</pre>
     *
     * <h2>Hot-path contract</h2>
     * <p>The list is {@link java.util.List#copyOf(java.util.Collection) immutable}.
     * Iteration is O(n) over a tiny list (typically 1–3 sinks). No lock, no allocation,
     * no virtual dispatch beyond the list iterator — acceptable for INFO/WARN paths.
     * JFR-backed sinks guard themselves with an {@code isEnabled()} check so that a
     * disabled JFR recording costs approximately zero nanoseconds.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<List<TelemetrySink>> TELEMETRY_SINKS = ScopedValue.newInstance();

    /**
     * The active {@link PersistenceProvider} factory (bound once during bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect or reconfigure
     * the provider. Application code should use {@link #PERSISTENCE_ENGINE} directly.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<PersistenceProvider> PERSISTENCE_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link PersistenceEngine} (created from {@link #PERSISTENCE_PROVIDER}).
     *
     * <p>This is the primary slot for all persistence operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * try (PersistenceConnection conn = KernelProviders.persistenceEngine().openConnection()) {
     *     try (QueryResult rs = conn.executeQuery("SELECT id, data FROM events")) {
     *         while (rs.next()) {
     *             int id = rs.row().getInt(0);      // zero-alloc (Enterprise)
     *             MemorySegment data = rs.row().getSegment(1); // zero-copy
     *         }
     *     }
     * }
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<PersistenceEngine> PERSISTENCE_ENGINE = ScopedValue.newInstance();

    // =========================================================================
    // Events Slots (L3 Logic Engines)
    // =========================================================================

    /**
     * The active {@link EventProvider} factory (bound once during bootstrap).
     *
     * <p>Populated by the kernel bootstrapper after {@link java.util.ServiceLoader} resolution
     * — the highest-priority {@link EventProvider} discovered on the classpath is selected
     * and bound here. Use this slot only in bootstrap code that needs to introspect or
     * reconfigure the provider. Application code should use {@link #EVENT_ENGINE} directly.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<EventProvider> EVENT_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link EventEngine} (created from the selected
     * {@link eu.exeris.kernel.spi.events.EventProvider}).
     *
     * <p>Bound once during bootstrap after {@link java.util.ServiceLoader} resolution.
     * All subsystems read this slot to publish and subscribe to kernel events.
     * The slot is inherited automatically by every virtual thread spawned within the
     * kernel scope — zero constructor coupling, zero static singletons.
     *
     * <h2>Usage (publishing)</h2>
     * <pre>{@code
     * EventEngine engine = KernelProviders.EVENT_ENGINE.get();
     * try (EventPayload payload = EventPayload.empty()) {
     *     engine.bus().publish(EventDescriptor.of(0, 0, 0, 0, 0, 0, 0), payload);
     * }
     * }</pre>
     *
     * <h2>Usage (subscribing)</h2>
     * <pre>{@code
     * SubscriptionToken token = engine.bus()
     *     .subscribe("TransportBound", (descriptor, payload) -> {
     *         try (payload) {
     *             handleBind(descriptor, payload);
     *         }
     *     });
     * }</pre>
     *
     * @since 0.5.0
     * @see eu.exeris.kernel.spi.events.EventEngine
     * @see eu.exeris.kernel.spi.events.EventProvider
     */
    public static final ScopedValue<EventEngine> EVENT_ENGINE = ScopedValue.newInstance();

    // =========================================================================
    // Flow Slots (L4 Saga / Flow Orchestration)
    // =========================================================================

    /**
     * The active {@link FlowProvider} factory (bound once during bootstrap).
     *
     * <p>Populated by the kernel bootstrapper after {@link java.util.ServiceLoader} resolution —
     * the highest-priority {@link FlowProvider} discovered on the classpath is selected
     * and bound here. Use this slot only in bootstrap code that needs to introspect or
     * reconfigure the provider. Application code should use {@link #FLOW_ENGINE} directly.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<FlowProvider> FLOW_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link FlowEngine} (created from the selected {@link FlowProvider}).
     *
     * <p>Bound once during bootstrap after {@link java.util.ServiceLoader} resolution.
     * All subsystems that trigger or inspect flows read this slot.
     * The slot is inherited automatically by every virtual thread spawned within the
     * kernel scope — zero constructor coupling, zero static singletons.
     *
     * <h2>Usage (scheduling a flow)</h2>
     * <pre>{@code
     * FlowEngine engine = KernelProviders.FLOW_ENGINE.get();
     * FlowExecutionPlan plan = engine.plans().compile(definition);
     * engine.scheduler().schedule(plan, context);
     * }</pre>
     *
     * @since 0.5.0
     * @see FlowEngine
     * @see FlowProvider
     */
    public static final ScopedValue<FlowEngine> FLOW_ENGINE = ScopedValue.newInstance();

    /**
     * The optional {@link FlowSnapshotStore} for persisting parked flow snapshots.
     *
     * <p>Bound by the bootstrapper <em>before</em> {@link FlowEngine#start()} is called,
     * when {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()} is
     * {@code true}. The {@link FlowEngine} reads this slot during {@code start()} and
     * wires the store into the PARK / LRU-eviction path.
     *
     * <p>If {@code persistenceEnabled} is {@code true} but this slot is unbound,
     * {@code FlowEngine.start()} MUST throw
     * {@link eu.exeris.kernel.spi.exceptions.flow.FlowEngineException}.
     * If {@code persistenceEnabled} is {@code false} this slot SHOULD be left unbound.
     *
     * <h2>Usage (bootstrapper side)</h2>
     * <pre>{@code
     * ScopedValue
     *     .where(KernelProviders.FLOW_ENGINE,         engine)
     *     .where(KernelProviders.FLOW_SNAPSHOT_STORE, myStore)
     *     .run(engine::start);
     * }</pre>
     *
     * <h2>Usage (engine / subsystem side)</h2>
     * <p>Preferred — via the typed convenience accessor:
     * <pre>{@code
     * KernelProviders.flowSnapshotStore()
     *     .ifPresent(store -> store.save(snapshot));
     * }</pre>
     * <p>Alternative — via the slot API directly:
     * <pre>{@code
     * if (KernelProviders.FLOW_SNAPSHOT_STORE.isBound()) {
     *     KernelProviders.FLOW_SNAPSHOT_STORE.get().save(snapshot);
     * }
     * }</pre>
     *
     * @since 0.5.0
     * @see FlowSnapshotStore
     * @see #flowSnapshotStore()
     * @see eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()
     */
    public static final ScopedValue<FlowSnapshotStore> FLOW_SNAPSHOT_STORE = ScopedValue.newInstance();

    /**
     * The optional {@link IdempotencyGuard} for flow-step deduplication.
     *
     * <p>Bound by the bootstrapper before {@link FlowEngine#start()} when step-level
     * idempotency tracking is required. If unbound, the engine falls back to a default
     * heap-based {@link IdempotencyGuard} implementation provided by the active tier.
     *
     * @since 0.6.0
     * @see IdempotencyGuard
     * @see #idempotencyGuard()
     */
    public static final ScopedValue<IdempotencyGuard> IDEMPOTENCY_GUARD = ScopedValue.newInstance();

    // =========================================================================
    // Transport Slots (L2 Native I/O)
    // =========================================================================

    /**
     * The active {@link TransportProvider} factory (bound once during bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect the provider.
     * Application code should use {@link #TRANSPORT_ENGINE} directly.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<TransportProvider> TRANSPORT_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link TransportEngine} (created from {@link #TRANSPORT_PROVIDER}).
     *
     * <p>This is the primary slot for all transport operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * TransportEngine engine = KernelProviders.TRANSPORT_ENGINE.get();
     * TransportConnection conn = engine.connect("remote-host", 443);
     * TransportStream stream = conn.openStream();
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<TransportEngine> TRANSPORT_ENGINE = ScopedValue.newInstance();

    // =========================================================================
    // Graph Slots (L2 Data Synthesis)
    // =========================================================================

    /**
     * The active {@link GraphProvider} factory (bound once during bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect the provider.
     * Application code should use {@link #GRAPH_ENGINE} directly.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<GraphProvider> GRAPH_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link GraphEngine} (created from {@link #GRAPH_PROVIDER}).
     *
     * <p>This is the primary slot for all graph operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * try (GraphSession session = KernelProviders.graphEngine().openSession()) {
     *     List<UUID> nodes = session.traverseBreadthFirst(traversal);
     * }
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<GraphEngine> GRAPH_ENGINE = ScopedValue.newInstance();

    // =========================================================================
    // Security / Context Slots (L1 Citadel)
    // =========================================================================

    /**
     * The active {@link SecurityProvider} (bound once during bootstrap).
     *
     * <p>Used by the transport edge to call
     * {@link SecurityProvider#authenticate(eu.exeris.kernel.spi.memory.LoanedBuffer)}
     * when a new request arrives. Application code should not access this directly —
     * it reads {@link #PRINCIPAL_CONTEXT} and {@link #STORAGE_CONTEXT} instead.
     *
     * @since 0.5.0
     */
    public static final ScopedValue<SecurityProvider> SECURITY_PROVIDER = ScopedValue.newInstance();

    /**
     * The authenticated {@link PrincipalContext} for the current request scope.
     *
     * <p>Re-bound per request by the transport/security interceptor. Every virtual
     * thread spawned within the request scope inherits this value automatically
     * (including children forked via {@link java.util.concurrent.StructuredTaskScope}).
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * PrincipalContext ctx = KernelProviders.PRINCIPAL_CONTEXT.get();
     * if (ctx.hasRole("ROLE_ADMIN")) { ... }
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<PrincipalContext> PRINCIPAL_CONTEXT = ScopedValue.newInstance();

    /**
     * The tenant-isolation {@link StorageContext} for the current request scope.
     *
     * <p>Re-bound per request alongside {@link #PRINCIPAL_CONTEXT}. The Persistence
     * layer reads this slot to inject RLS parameters or route connections — it never
     * imports any Security class directly.
     *
     * <h2>Usage (Persistence edge — ConnectionInterceptor)</h2>
     * <pre>{@code
     * // Correct: use the connection already checked out by the engine,
     * // do NOT open a new connection here (would leak).
     * void applyTenantIsolation(eu.exeris.kernel.spi.persistence.PersistenceConnection connection) {
     *     StorageContext sc = KernelProviders.STORAGE_CONTEXT.get();
     *     sc.isolationKey().ifPresent(key -> {
     *         try (eu.exeris.kernel.spi.persistence.PersistenceStatement stmt =
     *                 connection.prepare("SET LOCAL exeris.tenant_id = $1")) {
     *             stmt.bindString(0, key).executeUpdate();
     *         }
     *     });
     * }
     * }</pre>
     *
     * @since 0.5.0
     */
    public static final ScopedValue<StorageContext> STORAGE_CONTEXT = ScopedValue.newInstance();

    private KernelProviders() {
        // Utility class — static ScopedValue slots only, never instantiated.
    }

    /**
     * Returns the current carrier index, or {@code 0} if the value is not bound
     * (e.g., in unit test contexts outside a CarrierLoop scope).
     *
     * @return carrier index ≥ 0
     */
    public static int carrierIndex() {
        return CARRIER_INDEX.orElse(0);
    }

    /**
     * Returns the active {@link MemoryAllocator} from the current scope.
     *
     * @return allocator bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     */
    public static MemoryAllocator allocator() {
        return MEMORY_ALLOCATOR.get();
    }

    /**
     * Returns the active {@link PersistenceEngine} from the current scope.
     *
     * @return persistence engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if persistence was not bootstrapped
     */
    public static PersistenceEngine persistenceEngine() {
        return PERSISTENCE_ENGINE.get();
    }

    /**
     * Returns the active {@link TransportEngine} from the current scope.
     *
     * @return transport engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if transport was not bootstrapped
     */
    public static TransportEngine transportEngine() {
        return TRANSPORT_ENGINE.get();
    }

    /**
     * Returns the active {@link GraphEngine} from the current scope.
     *
     * @return graph engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if graph was not bootstrapped
     */
    public static GraphEngine graphEngine() {
        return GRAPH_ENGINE.get();
    }

    /**
     * Returns the active {@link EventEngine} from the current scope.
     *
     * @return event engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if events were not bootstrapped
     */
    public static EventEngine eventEngine() {
        return EVENT_ENGINE.get();
    }

    /**
     * Returns the active {@link EventProvider} from the current scope.
     *
     * @return event provider bound by the kernel bootstrapper during ServiceLoader resolution
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if events were not bootstrapped
     */
    public static EventProvider eventProvider() {
        return EVENT_PROVIDER.get();
    }

    /**
     * Returns the active {@link FlowEngine} from the current scope.
     *
     * @return flow engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if flow was not bootstrapped
     */
    public static FlowEngine flowEngine() {
        return FLOW_ENGINE.get();
    }

    /**
     * Returns the active {@link FlowProvider} from the current scope.
     *
     * @return flow provider bound by the kernel bootstrapper during ServiceLoader resolution
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if flow was not bootstrapped
     */
    public static FlowProvider flowProvider() {
        return FLOW_PROVIDER.get();
    }

    /**
     * Returns the optional {@link FlowSnapshotStore} from the current scope.
     *
     * @return an {@link Optional} containing the store if persistence is enabled
     *         and the slot was bound; empty otherwise
     */
    public static Optional<FlowSnapshotStore> flowSnapshotStore() {
        return FLOW_SNAPSHOT_STORE.isBound()
                ? Optional.of(FLOW_SNAPSHOT_STORE.get())
                : Optional.empty();
    }

    /**
     * Returns the optional {@link IdempotencyGuard} from the current scope.
     *
     * @return an {@link Optional} containing the guard if it was bound; empty otherwise
     */
    public static Optional<IdempotencyGuard> idempotencyGuard() {
        return IDEMPOTENCY_GUARD.isBound()
                ? Optional.of(IDEMPOTENCY_GUARD.get())
                : Optional.empty();
    }

    /**
     * Returns the active {@link PrincipalContext} from the current request scope.
     *
     * @return principal context bound by the security interceptor
     * @throws PrincipalContextMissingException if called outside a security scope
     */
    public static PrincipalContext principal() {
        return PRINCIPAL_CONTEXT.orElseThrow(PrincipalContextMissingException::new);
    }

    /**
     * Returns the active {@link StorageContext} from the current request scope.
     *
     * @return storage context bound by the security interceptor
     * @throws StorageContextMissingException if called outside a security scope
     */
    public static StorageContext storageContext() {
        return STORAGE_CONTEXT.orElseThrow(StorageContextMissingException::new);
    }

    /**
     * Returns the active {@link StorageContext} from the current request scope,
     * or the system-scope global context ({@link ImmutableStorageContext#GLOBAL}) if
     * the slot is not bound.
     *
     * <p>This method is intended <strong>exclusively</strong> for system-level and
     * bootstrap tasks (e.g., migrations, internal maintenance jobs) that legitimately
     * run outside a tenant request scope. It must <strong>never</strong> be used in
     * a request-handling path — doing so will silently disable tenant isolation and
     * bypass Row-Level Security, potentially leaking cross-tenant data.
     *
     * <p>Request-scoped code must use {@link #storageContext()}, which throws
     * {@link eu.exeris.kernel.spi.exceptions.security.StorageContextMissingException}
     * if the slot is unbound, making misconfiguration explicit and fail-fast.
     *
     * @return bound storage context, or {@link ImmutableStorageContext#GLOBAL}; never {@code null}
     * @apiNote Bootstrap / system tasks only. Do NOT call from request handlers or
     *          {@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor} implementations.
     */
    public static StorageContext storageContextOrSystem() {
        return STORAGE_CONTEXT.orElse(ImmutableStorageContext.GLOBAL);
    }

    /**
     * Returns the active {@link ConfigProvider} from the current kernel scope.
     *
     * <p>Available on every virtual thread after L0 bootstrap completes.
     *
     * @return config provider bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     */
    public static ConfigProvider config() {
        return CURRENT_CONFIG.get();
    }
}
