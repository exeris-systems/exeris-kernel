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

/** Minimal in-memory FlowSnapshotStore used by the Community runtime binding. */
public final class CommunityFlowSnapshotStore implements FlowSnapshotStore {

    private final ConcurrentMap<FlowKey, FlowSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(FlowSnapshot snapshot) {
        snapshots.put(new FlowKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
    }

    @Override
    public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
        return Optional.ofNullable(snapshots.get(new FlowKey(instanceIdMost, instanceIdLeast)));
    }

    @Override
    public void delete(long instanceIdMost, long instanceIdLeast) {
        snapshots.remove(new FlowKey(instanceIdMost, instanceIdLeast));
    }

    @Override
    public boolean exists(long instanceIdMost, long instanceIdLeast) {
        return snapshots.containsKey(new FlowKey(instanceIdMost, instanceIdLeast));
    }

    private record FlowKey(long instanceIdMost, long instanceIdLeast) {
    }
}