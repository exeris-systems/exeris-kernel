/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.Optional;

// ScopedValue is referenced in Javadoc only (@see KernelProviders#FLOW_SNAPSHOT_STORE)

/**
 * SPI: Optional persistence store for flow snapshots.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-blind</strong>. No reference to any
 * specific persistence driver, connection pool, or database product appears here.
 * Community implementations acquire a connection through
 * {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} obtained from
 * {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE}.
 * Enterprise implementations perform zero-copy snapshot writes directly from the
 * off-heap context slab slice via {@code MemorySegment}, without a heap byte[] copy.
 *
 * <h2>Discovery &amp; Wiring</h2>
 * <p>The bootstrapper binds a {@code FlowSnapshotStore} implementation to the
 * {@link eu.exeris.kernel.spi.context.KernelProviders#FLOW_SNAPSHOT_STORE}
 * {@link ScopedValue} slot <em>before</em> calling {@link eu.exeris.kernel.spi.flow.FlowEngine#start()}.
 * During {@code start()}, the engine reads that slot (if and only if
 * {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()} is {@code true})
 * and wires the store into the PARK / LRU-eviction path. If {@code persistenceEnabled} is
 * {@code true} but the slot is unbound, {@code FlowEngine.start()} MUST throw
 * {@link eu.exeris.kernel.spi.exceptions.flow.FlowEngineException}.
 *
 * <p>This pattern is identical to how every other optional SPI component
 * ({@link eu.exeris.kernel.spi.persistence.PersistenceEngine},
 * {@link eu.exeris.kernel.spi.telemetry.TelemetrySink}, etc.) is injected —
 * no constructor parameters, no magic DI, no {@code ServiceLoader} for the store itself.
 *
 * <h2>Call Contract</h2>
 * <p>Once wired, the engine calls {@link #save(FlowSnapshot)} asynchronously on the
 * PARK transition and synchronously on LRU eviction.
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.spi.context.KernelProviders#FLOW_SNAPSHOT_STORE
 */
public interface FlowSnapshotStore {

    /**
     * Persists the given flow snapshot.
     *
     * <p>May be called from any virtual thread. Implementations MUST be thread-safe.
     *
     * @param snapshot the snapshot to persist; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if persistence fails
     */
    void save(FlowSnapshot snapshot);

    /**
     * Loads a previously persisted flow snapshot.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return an {@link Optional} containing the snapshot if found, empty otherwise
     */
    Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast);

    /**
     * Deletes the snapshot for the given flow instance (called on successful completion).
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    void delete(long instanceIdMost, long instanceIdLeast);

    /**
     * Checks whether a snapshot exists for the given flow instance (idempotency guard).
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return {@code true} if a snapshot exists
     */
    boolean exists(long instanceIdMost, long instanceIdLeast);
}

