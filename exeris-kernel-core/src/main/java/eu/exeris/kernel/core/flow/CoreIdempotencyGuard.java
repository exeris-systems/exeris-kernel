/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.IdempotencyGuard;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default heap-backed {@link IdempotencyGuard} for the Community tier.
 *
 * <p>Claims are indexed by instance UUID (as a {@link FlowKey}) to a per-instance
 * {@code ConcurrentHashMap<Integer, Boolean>} of step indices.
 *
 * <p><b>Allocation:</b> allocates one inner {@code ConcurrentHashMap} per instance on its first
 * claim; per-step claims are map entries, not further allocations beyond boxing the step index.
 * <p><b>Thread confinement:</b> any thread — {@link #tryClaimStep} and {@link #releaseInstance} are
 * safe for concurrent calls across every flow instance the engine runs.
 * <p><b>Ownership:</b> this guard owns every claim it records; a claim is held until
 * {@link #releaseInstance} is called for that instance, and nothing else is released by a caller.
 *
 * @since 0.5
 */
final class CoreIdempotencyGuard implements IdempotencyGuard {

    private final ConcurrentMap<FlowKey, ConcurrentMap<Integer, Boolean>> claims = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * @implNote The compare-and-set the interface requires comes from
     *           {@link ConcurrentMap#putIfAbsent}: the winning call for a given step index is the
     *           one whose {@code putIfAbsent} observes an absent entry, which
     *           {@code ConcurrentHashMap} guarantees is exactly one caller regardless of how many
     *           race on it. The per-instance map itself is created lazily via
     *           {@link ConcurrentMap#computeIfAbsent} on first claim.
     */
    @Override
    public boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex) {
        FlowKey key = new FlowKey(instanceIdMost, instanceIdLeast);
        ConcurrentMap<Integer, Boolean> steps = claims.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        return steps.putIfAbsent(stepIndex, Boolean.TRUE) == null;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Removes the instance's whole inner map in one call, which is why this is O(1)
     *           rather than O(claimed steps) — there is no per-step entry to walk.
     */
    @Override
    public void releaseInstance(long instanceIdMost, long instanceIdLeast) {
        claims.remove(new FlowKey(instanceIdMost, instanceIdLeast));
    }
}
