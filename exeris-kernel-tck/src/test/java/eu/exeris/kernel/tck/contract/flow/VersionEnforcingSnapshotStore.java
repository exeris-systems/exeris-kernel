/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A snapshot store that enforces the durable half of {@code FlowSnapshot}'s optimistic-concurrency
 * contract: advance the row's version on every accepted write, reject an incoming version that does
 * not match the row.
 *
 * <h2>Why this is an instrument, not a case</h2>
 * <p>ADR-013 §5 makes {@code schemaVersion} the optimistic-lock counter, and lets an in-memory store
 * "ignore the field entirely". Every store a binding supplies to a TCK does exactly that — which
 * means the bookkeeping the runtime does around that field is invisible to the entire suite, and a
 * runtime that gets it wrong passes every migration case there is. What was missing was never a case
 * but something able to see one fail.
 *
 * <p>Conflicts are counted rather than thrown, because these writes happen on the engine's worker
 * threads: a throw there surfaces as a saga that quietly stopped, and the assertion would be reading
 * a symptom instead of the defect.
 *
 * <p>Lives here so every binding can reach it. Leaving it inside one module's test kept the
 * instrument next to the one runtime already known to be fixed — the arrangement that produced the
 * blind spot it exists to close.
 */
public final class VersionEnforcingSnapshotStore implements FlowSnapshotStore {

    private final Map<UUID, FlowSnapshot> rows = new ConcurrentHashMap<>();
    private final AtomicInteger conflicts = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();

    public void seed(FlowSnapshot snapshot) {
        rows.put(keyOf(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
    }

    public int conflicts() {
        return conflicts.get();
    }

    /** Attempts, conflicts included — so a caller waiting on write count is not hung by a rejection. */
    public int writes() {
        return writes.get();
    }

    @Override
    public void save(FlowSnapshot snapshot) {
        UUID key = keyOf(snapshot.instanceIdMost(), snapshot.instanceIdLeast());
        writes.incrementAndGet();
        rows.compute(key, (_, current) -> {
            if (current != null && current.schemaVersion() != snapshot.schemaVersion()) {
                conflicts.incrementAndGet();
                return current;
            }
            return withSchemaVersion(snapshot, snapshot.schemaVersion() + 1);
        });
    }

    @Override
    public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
        return Optional.ofNullable(rows.get(keyOf(instanceIdMost, instanceIdLeast)));
    }

    @Override
    public void delete(long instanceIdMost, long instanceIdLeast) {
        rows.remove(keyOf(instanceIdMost, instanceIdLeast));
    }

    @Override
    public boolean exists(long instanceIdMost, long instanceIdLeast) {
        return rows.containsKey(keyOf(instanceIdMost, instanceIdLeast));
    }

    private static UUID keyOf(long most, long least) {
        return new UUID(most, least);
    }

    private static FlowSnapshot withSchemaVersion(FlowSnapshot from, long schemaVersion) {
        return new FlowSnapshot(
                from.instanceIdMost(), from.instanceIdLeast(),
                from.definitionName(), from.definitionVersion(),
                from.currentStep(), from.currentStepName(),
                from.state(), from.lastUpdate(), from.timeout(),
                from.compensationStack(), from.compensationStepNames(), from.stackPointer(),
                from.opaqueState(), schemaVersion);
    }
}
