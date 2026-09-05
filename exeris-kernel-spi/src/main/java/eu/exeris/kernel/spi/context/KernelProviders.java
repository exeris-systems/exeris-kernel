/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.context;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry;
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
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.time.TimeSource;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportProvider;

import java.util.List;
import java.util.Optional;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerProvider;

/**
 * Central {@link ScopedValue} slots for all SPI providers resolved during bootstrap.
 *
 * <h2>Zero Static Singletons (The Wall)</h2>
 * <p>The kernel keeps no mutable static provider state: no singletons, no double-checked
 * locking, and no {@code ThreadLocal} caches. Every subsystem reads its provider from the
 * scoped slot that was bound by the kernel bootstrapper.
 *
 * <h2>Context Propagation Model (JEP 506)</h2>
 * <p>{@code ScopedValue} slots are inherited by every {@link Thread#startVirtualThread(Runnable) virtual thread}
 * spawned within the binding scope. This means a single {@code ScopedValue.where(...).run(...)}
 * in {@code KernelBootstrap} covers the entire lifetime of the kernel — thousands of virtual
 * threads all read the same provider instances with zero synchronisation overhead.
 *
 * <h2>Binding (bootstrap side)</h2>
 * {@snippet lang="java" :
 * ScopedValue
 *     .where(KernelProviders.CURRENT_CONFIG,   configProvider)   // L0 — bound first
 *     .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
 *     .where(KernelProviders.MEMORY_PROVIDER,  provider)
 *     .where(KernelProviders.CARRIER_INDEX, 0)
 *     .run(kernel::startSubsystems);
 * }
 *
 * <h2>Reading (subsystem / handler side)</h2>
 * {@snippet lang="java" :
 * LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
 *     .allocate(AllocationHint.MEDIUM);
 * }
 *
 * <h2>CarrierLoop affinity</h2>
 * <p>{@link #CARRIER_INDEX} is re-bound per carrier loop iteration so that the
 * carrier-affine slab pool selection in {@link MemoryAllocator#allocateCarrierSlab(int)}
 * requires no argument threading — the index flows via {@code ScopedValue}.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — reading a slot ({@code get()} or
 * {@code orElse}) allocates nothing; the {@link Optional}-returning accessors on this class
 * allocate one {@code Optional} per call when the slot they read is bound.
 * <p><b>Thread confinement:</b> any thread — every slot is readable from any thread executing
 * inside the binding scope, including the virtual threads that inherit the binding; outside
 * that scope every slot is unbound.
 * <p><b>Ownership:</b> the kernel bootstrapper owns each bound provider, engine and context,
 * and their lifecycle; a reader borrows the reference for the duration of the binding scope
 * and neither closes nor restarts it. A resource obtained <em>from</em> a bound instance — a
 * {@code LoanedBuffer}, a connection, an {@code EventPayload} — is owned by the caller that
 * obtained it and is released there.
 *
 * @since 0.5
 * @see <a href="../../../../../../docs/subsystems/memory.md">memory.md</a>
 */
// Central ScopedValue slot registry — it imports every SPI provider/engine type by
// design; splitting it would violate the SPI Wall, so the import/method counts are intrinsic.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports"})
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
     * {@snippet lang="java" :
     * ConfigProvider.KernelSettings settings =
     *         KernelProviders.config().kernelSettings().get();
     * int port = settings.network().port();
     * }
     *
     * @apiNote Reach the binding through the typed accessor {@link #config()} rather than the
     *          slot constant; both resolve the same value and both throw
     *          {@link java.util.NoSuchElementException} outside the kernel scope.
     * @since 0.5
     */
    public static final ScopedValue<ConfigProvider> CURRENT_CONFIG = ScopedValue.newInstance();

    /**
     * The active {@link MemoryProvider} factory (bound once during bootstrap).
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect or reconfigure the
     *          provider; an allocation call site reads {@link #MEMORY_ALLOCATOR} instead.
     */
    public static final ScopedValue<MemoryProvider> MEMORY_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link MemoryAllocator} (created from {@link #MEMORY_PROVIDER}).
     *
     * <p>This is the primary slot for all allocation calls. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * {@snippet lang="java" :
     * try (LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
     *         .allocate(AllocationHint.SMALL)) {
     *     // zero-copy processing
     * }
     * }
     *
     * @apiNote The buffer belongs to the caller that allocated it — take it in
     *          {@code try}-with-resources as above; a missed {@code close()} is a silent leak.
     */
    public static final ScopedValue<MemoryAllocator> MEMORY_ALLOCATOR = ScopedValue.newInstance();

    /**
     * Zero-based index of the current carrier thread within the CarrierLoop pool.
     *
     * <p>Re-bound by the CarrierLoop dispatcher on every iteration so that
     * {@link MemoryAllocator#allocateCarrierSlab(int)} can select the NUMA-local
     * slab pool without requiring an explicit argument at every call site.
     *
     * <p>A read through {@link #carrierIndex()} yields {@code 0} when the slot was not bound by
     * a CarrierLoop (during a unit test, for example).
     *
     * @implNote The Community {@link MemoryAllocator} ignores {@code carrierIndex} entirely and
     *           always serves its single shared pool, so index {@code 0} (like every other
     *           value) already works without special-casing. This SPI places no other
     *           constraint on how a multi-pool implementation handles an unbound-scope caller
     *           that reads index {@code 0} via {@link #carrierIndex()}.
     */
    public static final ScopedValue<Integer> CARRIER_INDEX = ScopedValue.newInstance();

    /**
     * The active {@link KernelCryptoProvider} (bound once during bootstrap).
     *
     * <p>Transport subsystems read this slot to create {@link eu.exeris.kernel.spi.crypto.TlsEngine}
     * instances.
     *
     * @implNote The kernel bootstrapper also reads {@link KernelCryptoProvider#supportsQuic()}
     *           from this slot to decide whether to activate QUIC transport.
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
     * <p>The provider is a factory whose work ends once {@code createSinks()} has returned;
     * binding the pre-built list means zero indirection and zero object creation on the emit
     * path. The list is {@link java.util.List#copyOf(java.util.Collection) immutable} and tiny
     * (typically 1–3 sinks): iteration is O(n) with no lock, no allocation and no virtual
     * dispatch beyond the list iterator — acceptable for INFO/WARN paths.
     *
     * {@snippet lang="java" :
     * for (TelemetrySink sink : KernelProviders.TELEMETRY_SINKS.get()) {
     *     sink.emit(event);
     * }
     * }
     *
     * @apiNote Subsystems (Transport, Persistence, Crypto) read this slot and never call
     *          {@code createSinks()} again. Iterate with an explicit {@code for} loop as above;
     *          a lambda passed to {@code forEach} may allocate a new instance per call on some
     *          JVM builds.
     * @implNote JFR-backed sinks guard themselves with an {@code isEnabled()} check, so a
     *           disabled JFR recording costs approximately zero nanoseconds.
     * @since 0.5
     */
    public static final ScopedValue<List<TelemetrySink>> TELEMETRY_SINKS = ScopedValue.newInstance();

    /**
     * The active {@link PersistenceProvider} factory (bound once during bootstrap).
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect or reconfigure the
     *          provider; a persistence call site reads {@link #PERSISTENCE_ENGINE} instead.
     * @since 0.5
     */
    public static final ScopedValue<PersistenceProvider> PERSISTENCE_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link PersistenceEngine} (created from {@link #PERSISTENCE_PROVIDER}).
     *
     * <p>This is the primary slot for all persistence operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * {@snippet lang="java" :
     * try (PersistenceConnection conn = KernelProviders.persistenceEngine().openConnection()) {
     *     try (QueryResult rs = conn.executeQuery("SELECT id, data FROM events")) {
     *         while (rs.next()) {
     *             int id = rs.row().getInt(0);      // zero-alloc (Enterprise)
     *             MemorySegment data = rs.row().getSegment(1); // zero-copy
     *         }
     *     }
     * }
     * }
     *
     * @since 0.5
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
     * and bound here.
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect or reconfigure the
     *          provider; an event call site reads {@link #EVENT_ENGINE} instead.
     * @since 0.5
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
     * <p>Publishing:
     * {@snippet lang="java" :
     * EventEngine engine = KernelProviders.EVENT_ENGINE.get();
     * try (EventPayload payload = EventPayload.empty()) {
     *     engine.bus().publish(EventDescriptor.of(0, 0, 0, 0, 0, 0, 0), payload);
     * }
     * }
     *
     * <p>Subscribing:
     * {@snippet lang="java" :
     * SubscriptionToken token = engine.bus()
     *     .subscribe("TransportBound", (descriptor, payload) -> {
     *         try (payload) {
     *             handleBind(descriptor, payload);
     *         }
     *     });
     * }
     *
     * @since 0.5
     * @see eu.exeris.kernel.spi.events.EventEngine
     * @see eu.exeris.kernel.spi.events.EventProvider
     */
    public static final ScopedValue<EventEngine> EVENT_ENGINE = ScopedValue.newInstance();

    /**
     * Optional {@link EventStreamReader} for replay over the durable event log.
     *
     * <p>Bound by the bootstrapper before {@link EventEngine#start()} when a binding
     * (e.g. PostgreSQL outbox replay, Kafka consumer-seek driver) is on the classpath.
     *
     * @apiNote Application code consults {@link #eventStreamReader()} and treats an empty
     *          {@link Optional} as "this broker does not support replay" — never as a hard error.
     * @since 0.7
     * @see EventStreamReader
     */
    public static final ScopedValue<EventStreamReader> EVENT_STREAM_READER = ScopedValue.newInstance();

    /**
     * Optional {@link EventStreamAppender} for direct durable append.
     *
     * <p>Bound by the bootstrapper before {@link EventEngine#start()} when a binding
     * (e.g. Kafka producer with explicit partition control) is on the classpath.
     *
     * @apiNote Most callers route through the transactional outbox; read this slot only at a site
     *          that needs explicit topic or partition routing.
     * @since 0.7
     * @see EventStreamAppender
     */
    public static final ScopedValue<EventStreamAppender> EVENT_STREAM_APPENDER = ScopedValue.newInstance();

    /**
     * Optional {@link EventPayloadCodecRegistry} for serializing typed domain-event
     * payloads to the bytes the {@link EventEngine} carries (ADR-046).
     *
     * <p>Bound by the bootstrapper before {@link EventEngine#start()} when a codec
     * binding (e.g. the Community JSON driver) is on the classpath. Resolved by the
     * <b>producer</b> — the generated {@code *EventPublisher} — via
     * {@link #eventPayloadCodecRegistry()} (ADR-036 "site B"); {@code EventBus} /
     * {@code EventEngine} carry no codec knowledge. The slot is <b>optional</b>: a
     * kernel without a codec binding still bootstraps events. Inherited by every
     * virtual thread in the kernel scope (the publish-path threads where the generated
     * publisher runs).
     *
     * @apiNote A producer treats an empty {@link Optional} as "no codec configured" and falls
     *          back to {@link eu.exeris.kernel.spi.events.EventPayload#empty()}.
     * @since 0.10
     * @see eu.exeris.kernel.spi.events.codec.EventPayloadCodec
     * @see #eventPayloadCodecRegistry()
     */
    public static final ScopedValue<EventPayloadCodecRegistry> EVENT_PAYLOAD_CODEC_REGISTRY =
            ScopedValue.newInstance();

    // =========================================================================
    // Flow Slots (L4 Saga / Flow Orchestration)
    // =========================================================================

    /**
     * The active {@link FlowProvider} factory (bound once during bootstrap).
     *
     * <p>Populated by the kernel bootstrapper after {@link java.util.ServiceLoader} resolution —
     * the highest-priority {@link FlowProvider} discovered on the classpath is selected
     * and bound here.
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect or reconfigure the
     *          provider; a flow call site reads {@link #FLOW_ENGINE} instead.
     * @since 0.5
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
     * <p>Scheduling a flow:
     * {@snippet lang="java" :
     * FlowEngine engine = KernelProviders.FLOW_ENGINE.get();
     * FlowExecutionPlan plan = engine.plans().compile(definition);
     * engine.scheduler().schedule(plan, context);
     * }
     *
     * @since 0.5
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
     * wires the store into the PARK / LRU-eviction path. When {@code persistenceEnabled}
     * is {@code false} this slot should be left unbound.
     *
     * <p>Binding it (bootstrapper side):
     * {@snippet lang="java" :
     * ScopedValue
     *     .where(KernelProviders.FLOW_ENGINE,         engine)
     *     .where(KernelProviders.FLOW_SNAPSHOT_STORE, myStore)
     *     .run(engine::start);
     * }
     *
     * <p>Reading it (engine / subsystem side) — preferred, via the typed convenience accessor:
     * {@snippet lang="java" :
     * KernelProviders.flowSnapshotStore()
     *     .ifPresent(store -> store.save(snapshot));
     * }
     *
     * <p>Alternative — via the slot API directly:
     * {@snippet lang="java" :
     * if (KernelProviders.FLOW_SNAPSHOT_STORE.isBound()) {
     *     KernelProviders.FLOW_SNAPSHOT_STORE.get().save(snapshot);
     * }
     * }
     *
     * @implSpec {@link FlowEngine#start()} must throw
     *           {@link eu.exeris.kernel.spi.exceptions.flow.FlowEngineException}
     *           ({@code EX-FLOW-7002}) when {@code persistenceEnabled} is {@code true} and this
     *           slot is unbound.
     * @since 0.5
     * @see FlowSnapshotStore
     * @see #flowSnapshotStore()
     * @see eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()
     */
    public static final ScopedValue<FlowSnapshotStore> FLOW_SNAPSHOT_STORE = ScopedValue.newInstance();

    /**
     * The optional {@link IdempotencyGuard} for preventing duplicate step execution.
     *
     * <p>Bound by the bootstrapper before {@link FlowEngine#start()} when a custom
     * guard is required.
     *
     * @implNote With the slot unbound, the default {@link FlowEngine} implementation installs a
     *           heap-backed guard.
     * @since 0.5
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
     * @apiNote Read this slot only from bootstrap code that has to introspect the provider; a
     *          transport call site reads {@link #TRANSPORT_ENGINE} instead.
     * @since 0.5
     */
    public static final ScopedValue<TransportProvider> TRANSPORT_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link TransportEngine} (created from {@link #TRANSPORT_PROVIDER}).
     *
     * <p>This is the primary slot for all transport operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * {@snippet lang="java" :
     * TransportEngine engine = KernelProviders.TRANSPORT_ENGINE.get();
     * TransportConnection conn = engine.connect("remote-host", 443);
     * TransportStream stream = conn.openStream();
     * }
     *
     * @since 0.5
     */
    public static final ScopedValue<TransportEngine> TRANSPORT_ENGINE = ScopedValue.newInstance();

    // =========================================================================
    // Graph Slots (L2 Data Synthesis)
    // =========================================================================

    /**
     * The active {@link GraphProvider} factory (bound once during bootstrap).
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect the provider; a
     *          graph call site reads {@link #GRAPH_ENGINE} instead.
     * @since 0.5
     */
    public static final ScopedValue<GraphProvider> GRAPH_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link GraphEngine} (created from {@link #GRAPH_PROVIDER}).
     *
     * <p>This is the primary slot for all graph operations. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * {@snippet lang="java" :
     * try (GraphSession session = KernelProviders.graphEngine().openSession()) {
     *     List<UUID> nodes = session.traverseBreadthFirst(traversal);
     * }
     * }
     *
     * @since 0.5
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
     * when a new request arrives.
     *
     * @apiNote Application code does not read this slot: the authenticated outcome reaches it as
     *          {@link #PRINCIPAL_CONTEXT} and {@link #STORAGE_CONTEXT}.
     * @since 0.5
     */
    public static final ScopedValue<SecurityProvider> SECURITY_PROVIDER = ScopedValue.newInstance();

    /**
     * The selected {@link eu.exeris.kernel.spi.scheduling.JobSchedulerProvider} (ADR-057 §1).
     *
     * <p>Bound once at bootstrap.
     *
     * @apiNote Read this slot only for diagnostics or to reconfigure the provider; a job
     *          submission reads {@link #JOB_SCHEDULER} instead.
     * @since 0.11
     */
    public static final ScopedValue<JobSchedulerProvider> JOB_SCHEDULER_PROVIDER =
            ScopedValue.newInstance();

    /**
     * The kernel-wide {@link eu.exeris.kernel.spi.scheduling.JobScheduler} (created from
     * {@link #JOB_SCHEDULER_PROVIDER}).
     *
     * <p>Jobs submitted through it capture the ambient {@code PrincipalContext} and
     * {@code StorageContext} and rebind them at dispatch; a submission with neither bound fails
     * closed rather than running as nobody (ADR-057 §5).
     *
     * @since 0.11
     */
    public static final ScopedValue<JobScheduler> JOB_SCHEDULER = ScopedValue.newInstance();

    /**
     * The selected {@link eu.exeris.kernel.spi.storage.blob.BlobStorageProvider} (ADR-056).
     *
     * <p>Bound once at bootstrap, and only when blob storage is configured — the two Community
     * drivers register at the same priority, so nothing is selected until an operator names one.
     *
     * @since 0.12
     */
    public static final ScopedValue<BlobStorageProvider> BLOB_STORAGE_PROVIDER =
            ScopedValue.newInstance();

    /**
     * The kernel-wide {@link eu.exeris.kernel.spi.storage.blob.BlobStore} created from
     * {@link #BLOB_STORAGE_PROVIDER}.
     *
     * <p>Unbound in a deployment that has not configured blob storage, which is the normal case.
     *
     * @apiNote Read it with {@code orElse}, not {@code get}, unless the caller already knows
     *          storage is on. The slot is named {@code BLOB_*} rather than {@code STORAGE_*}
     *          deliberately: {@link #STORAGE_CONTEXT} is ADR-012's tenant-isolation carrier and
     *          has nothing to do with object storage. Two things called storage that mean
     *          different things is a naming collision worth one extra word.
     * @since 0.12
     */
    public static final ScopedValue<BlobStore> BLOB_STORE = ScopedValue.newInstance();

    /**
     * Where the kernel reads time it will decide on (ADR-082).
     *
     * <p>Bound once at bootstrap.
     *
     * @apiNote Read it through {@link #timeSource()} rather than directly: an unbound kernel must
     *          still tell the time, and a call site that forgets its own {@code orElse} looks
     *          migrated while remaining undrivable.
     * @since 0.12
     */
    public static final ScopedValue<TimeSource> TIME_SOURCE = ScopedValue.newInstance();

    /**
     * The authenticated {@link PrincipalContext} for the current request scope.
     *
     * <p>Re-bound per request by the transport/security interceptor. Every virtual
     * thread spawned within the request scope inherits this value automatically
     * (including children forked via {@link java.util.concurrent.StructuredTaskScope}).
     *
     * {@snippet lang="java" :
     * PrincipalContext ctx = KernelProviders.PRINCIPAL_CONTEXT.get();
     * if (ctx.hasRole("ROLE_ADMIN")) {
     *     grantAdminView();
     * }
     * }
     *
     * @since 0.5
     */
    public static final ScopedValue<PrincipalContext> PRINCIPAL_CONTEXT = ScopedValue.newInstance();

    /**
     * The tenant-isolation {@link StorageContext} for the current request scope.
     *
     * <p>Re-bound per request alongside {@link #PRINCIPAL_CONTEXT}. The Persistence
     * layer reads this slot to inject RLS parameters or route connections — it never
     * imports any Security class directly.
     *
     * <p>At the persistence edge, in a {@code ConnectionInterceptor}:
     * {@snippet lang="java" :
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
     * }
     *
     * @since 0.5
     */
    public static final ScopedValue<StorageContext> STORAGE_CONTEXT = ScopedValue.newInstance();

    /**
     * The active subsystem inventory, in the orchestrator's topological order.
     *
     * <p>Bound once by the bootstrap when the kernel scope is built, so in-process, read-only
     * introspection (the {@code KernelDiagnostics} SPI — ADR-033) can describe the bootstrap DAG,
     * the resolved composition, and per-subsystem detail without reaching into
     * {@code exeris-kernel-core} (which would break The Wall) or treating {@code SubsystemOrchestrator}
     * public methods as a shadow SPI. May be unbound on a kernel that registered no subsystem
     * bindings — callers must tolerate {@link ScopedValue#isBound()} returning {@code false}.
     *
     * @apiNote Read it only on the cold diagnostic path, never on a request hot path, and treat
     *          the {@link Subsystem} instances as read-only: never invoke their lifecycle methods
     *          ({@code initialize()} / {@code start()} / {@code stop()}). Lifecycle is owned solely
     *          by the bootstrap orchestrator.
     * @since 0.9
     */
    public static final ScopedValue<List<Subsystem>> SUBSYSTEMS = ScopedValue.newInstance();

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
     * Returns the kernel-wide {@link MemoryAllocator} bound for the enclosing scope — the one
     * allocator through which every subsystem obtains off-heap memory.
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
     * Returns the optional {@link EventStreamReader} from the current scope.
     *
     * @return an {@link Optional} containing the reader if a binding is present and
     *         the slot was bound; empty otherwise
     * @since 0.7
     */
    public static Optional<EventStreamReader> eventStreamReader() {
        return EVENT_STREAM_READER.isBound()
                ? Optional.of(EVENT_STREAM_READER.get())
                : Optional.empty();
    }

    /**
     * Returns the optional {@link EventStreamAppender} from the current scope.
     *
     * @return an {@link Optional} containing the appender if a binding is present and
     *         the slot was bound; empty otherwise
     * @since 0.7
     */
    public static Optional<EventStreamAppender> eventStreamAppender() {
        return EVENT_STREAM_APPENDER.isBound()
                ? Optional.of(EVENT_STREAM_APPENDER.get())
                : Optional.empty();
    }

    /**
     * Returns the optional {@link EventPayloadCodecRegistry} from the current scope (ADR-046).
     *
     * @return an {@link Optional} containing the registry if a codec binding is present
     *         and the slot was bound; empty otherwise
     * @since 0.10
     */
    public static Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
        return EVENT_PAYLOAD_CODEC_REGISTRY.isBound()
                ? Optional.of(EVENT_PAYLOAD_CODEC_REGISTRY.get())
                : Optional.empty();
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
     * @return an {@link Optional} containing the guard if the slot was bound; empty otherwise
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
     *         ({@code EX-SEC-2001})
     */
    public static PrincipalContext principal() {
        return PRINCIPAL_CONTEXT.orElseThrow(PrincipalContextMissingException::new);
    }

    /**
     * Returns the active {@link StorageContext} from the current request scope.
     *
     * @return storage context bound by the security interceptor
     * @throws StorageContextMissingException if called outside a security scope
     *         ({@code EX-SEC-2004})
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
     * ({@code EX-SEC-2004}) if the slot is unbound, making misconfiguration explicit
     * and fail-fast.
     *
     * @return bound storage context, or {@link ImmutableStorageContext#GLOBAL}; never {@code null}
     * @apiNote Bootstrap / system tasks only. Do NOT call from request handlers or
     *          {@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor} implementations.
     */
    public static StorageContext storageContextOrSystem() {
        return STORAGE_CONTEXT.orElse(ImmutableStorageContext.GLOBAL);
    }

    /**
     * Returns the bound {@link TimeSource}, or the platform clock when none is bound.
     *
     * <p>Unbound is the ordinary case for anything running outside a kernel scope — a test, a
     * standalone driver — so this returns a default rather than throwing. Deciding reads call this;
     * measuring reads call {@link System#nanoTime()} directly, which ADR-082 rules on.
     *
     * @return the bound source, or {@link TimeSource#SYSTEM}; never {@code null}
     * @since 0.12
     */
    public static TimeSource timeSource() {
        return TIME_SOURCE.orElse(TimeSource.SYSTEM);
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
