/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.Optional;

/**
 * SPI: Optional persistence store for flow snapshots.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-blind</strong>. No reference to JDBC,
 * {@code HikariCP}, {@code RlsDataSource}, {@code PostgreSQL}, or any driver appears
 * here. Community implementations use
 * {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} from
 * {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE}.
 * Enterprise implementations perform zero-copy snapshot writes directly from the
 * off-heap context slab slice via {@code MemorySegment}, without a heap byte[] copy.
 *
 * <h2>Registration</h2>
 * <p>Registered with the {@link eu.exeris.kernel.spi.flow.FlowEngine} during bootstrap
 * when {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()} is
 * {@code true}. The engine calls {@link #save(FlowSnapshot)} asynchronously on
 * the PARK transition and synchronously on LRU eviction.
 *
 * @since 0.5.0
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

