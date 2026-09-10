/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Minimal in-memory {@link FlowSnapshotStore} keyed by flow instance UUID, used by the Community
 * runtime binding.
 *
 * <p>Snapshots exist only for the lifetime of this JVM process; {@code listParked()} is not
 * overridden, so it returns the empty list per {@link FlowSnapshotStore}'s default contract for
 * stores that do not survive a restart.
 */
public final class CommunityFlowSnapshotStore implements FlowSnapshotStore {

    private final ConcurrentMap<FlowKey, FlowSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Built by {@code CommunityFlowSubsystem} as the heap-resident snapshot store used when no
     * {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} is bound at boot, and directly by
     * tests that need a fresh, isolated in-memory store.
     */
    public CommunityFlowSnapshotStore() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Stores {@code snapshot} under its instance UUID, replacing any snapshot previously stored
     * for the same instance.
     *
     * @param snapshot the snapshot to persist
     */
    @Override
    public void save(FlowSnapshot snapshot) {
        snapshots.put(new FlowKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
    }

    /**
     * Returns the snapshot currently stored for the given flow instance, if any.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return the stored snapshot, or empty if none is stored for this instance
     */
    @Override
    public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
        return Optional.ofNullable(snapshots.get(new FlowKey(instanceIdMost, instanceIdLeast)));
    }

    /**
     * Removes the snapshot stored for the given flow instance, if any.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    @Override
    public void delete(long instanceIdMost, long instanceIdLeast) {
        snapshots.remove(new FlowKey(instanceIdMost, instanceIdLeast));
    }

    /**
     * Returns {@code true} if a snapshot is currently stored for the given flow instance.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return whether a snapshot is stored for this instance
     */
    @Override
    public boolean exists(long instanceIdMost, long instanceIdLeast) {
        return snapshots.containsKey(new FlowKey(instanceIdMost, instanceIdLeast));
    }

    private record FlowKey(long instanceIdMost, long instanceIdLeast) {
    }
}