/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.List;
import java.util.Optional;

// ScopedValue is referenced in Javadoc only (@see KernelProviders#FLOW_SNAPSHOT_STORE)

/**
 * SPI: the durable side of a saga — where a parked flow instance is written so that a restart, or
 * another engine sharing the same store, can pick it up again.
 *
 * <p>Persistence is optional. The store is used only when one is bound and
 * {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()} is {@code true}; without
 * it a flow lives and dies inside one engine's memory, and cross-restart choreography wake is
 * unsupported by contract.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-blind</strong>. No reference to any
 * specific persistence driver, connection pool, or database product appears here.
 *
 * <h2>Discovery &amp; Wiring</h2>
 * <p>The bootstrapper binds a {@code FlowSnapshotStore} implementation to the
 * {@link eu.exeris.kernel.spi.context.KernelProviders#FLOW_SNAPSHOT_STORE}
 * {@link ScopedValue} slot <em>before</em> calling {@link eu.exeris.kernel.spi.flow.FlowEngine#start()}.
 * During {@code start()}, the engine reads that slot (if and only if
 * {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#persistenceEnabled()} is {@code true})
 * and wires the store into the PARK / LRU-eviction path. If {@code persistenceEnabled} is
 * {@code true} but the slot is unbound, {@code FlowEngine.start()} MUST throw
 * {@link eu.exeris.kernel.spi.exceptions.flow.FlowEngineException} ({@code EX-FLOW-7002}).
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
 * <p><b>Allocation:</b> allocates — {@link #load} and {@link #listParked()} materialise
 * {@link FlowSnapshot} carriers and the defensive array copies each one makes. A write may be
 * zero-copy in a binding that streams from the off-heap slab. Every method here is on the cold
 * persistence path (PARK, eviction, restart recovery), never on step dispatch.
 * <p><b>Thread confinement:</b> any thread — {@link #save} may be called from any virtual thread,
 * and implementations are required to be thread-safe.
 * <p><b>Ownership:</b> whatever bound the store owns it. This interface has no {@code close()}, so a
 * store holding a pool or a file handle is released by its binder, never by the engine, which only
 * reads the {@code FLOW_SNAPSHOT_STORE} slot. Snapshots handed back are plain immutable values with
 * nothing to release.
 *
 * @implNote A Community implementation acquires its connection through
 *           {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}, obtained from
 *           {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE}. An Enterprise
 *           implementation writes a snapshot zero-copy, straight from the off-heap context slab
 *           slice via {@code MemorySegment}, with no intermediate heap {@code byte[]}.
 * @since 0.5
 * @see eu.exeris.kernel.spi.context.KernelProviders#FLOW_SNAPSHOT_STORE
 */
public interface FlowSnapshotStore {

    /**
     * Writes the snapshot durably, so the instance it describes survives the process that produced
     * it. A row already held for the same instance is replaced.
     *
     * @param snapshot the snapshot to persist; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException ({@code EX-FLOW-7002}) if the
     *         write fails or is refused. A durable store refuses a stale
     *         {@link FlowSnapshot#schemaVersion()} with {@code phase="OPTIMISTIC_LOCK_CONFLICT"} and
     *         {@code reasonCode="STALE_VERSION"}
     * @implSpec May be called from any virtual thread; implementations MUST be thread-safe. A
     *           durable, distributed store MUST advance the on-disk {@code schemaVersion} by one on
     *           every accepted write and MUST reject a write whose incoming version does not match
     *           the row it addresses.
     */
    void save(FlowSnapshot snapshot);

    /**
     * Reads back the snapshot for one flow instance, if this store holds one.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return the stored snapshot, or {@link Optional#empty()} when this store holds none for that
     *         instance — which is an ordinary answer, not an error: the instance may belong to
     *         another engine's catalogue, or have completed and had its row reclaimed
     */
    Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast);

    /**
     * Reclaims the row for one flow instance, so a later restart does not rediscover a saga that has
     * already finished.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @apiNote The engine calls this on completion only while its terminal-state catalogue is
     *          unbounded. Under a bounded catalogue the completed row is deliberately kept, because
     *          it is the only proof of completion that survives an eviction.
     */
    void delete(long instanceIdMost, long instanceIdLeast);

    /**
     * Reports whether this store holds a row for the given flow instance, without materialising it.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return {@code true} when a snapshot for that instance is stored
     */
    boolean exists(long instanceIdMost, long instanceIdLeast);

    /**
     * Enumerates every instance this store holds in {@link FlowState#PARKED} — the sagas a restarted
     * or a peer engine may still be asked to wake (see ADR-013).
     *
     * @return every parked snapshot; an empty list when the store holds none, and equally when it
     *         does not survive a restart and therefore tracks none
     * @implSpec The default returns an empty list, which is the correct answer for an in-memory store
     *           that does not outlive its process. A durable, distributed store MUST override this
     *           and enumerate every parked row, or the engine cannot resume choreography across a
     *           restart. Implementations MUST be thread-safe, and the returned list MUST be a
     *           point-in-time view: a mutation the store accepts after this call returns MUST NOT be
     *           visible in it.
     * @apiNote Cold path — one call at engine startup, and one per miss on the in-memory
     *          parked-instance index. An implementation MAY materialise the whole list; pagination is
     *          not part of the contract.
     * @since 0.7
     */
    default List<FlowSnapshot> listParked() {
        return List.of();
    }
}

