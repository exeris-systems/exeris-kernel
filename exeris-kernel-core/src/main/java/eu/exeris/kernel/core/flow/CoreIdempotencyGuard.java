/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.IdempotencyGuard;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default heap-backed {@link IdempotencyGuard} for the Community tier.
 *
 * <p>Claims are indexed by instance UUID (as a {@code FlowKey}) to a per-instance
 * {@code ConcurrentHashMap<Integer, Boolean>} of step indices. This makes
 * {@link #releaseInstance} O(1) (remove the whole inner map) rather than O(total_claimed_steps).
 *
 * @since 0.5.0
 */
final class CoreIdempotencyGuard implements IdempotencyGuard {

    private final ConcurrentMap<FlowKey, ConcurrentMap<Integer, Boolean>> claims = new ConcurrentHashMap<>();

    @Override
    public boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex) {
        FlowKey key = new FlowKey(instanceIdMost, instanceIdLeast);
        ConcurrentMap<Integer, Boolean> steps = claims.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        return steps.putIfAbsent(stepIndex, Boolean.TRUE) == null;
    }

    @Override
    public void releaseInstance(long instanceIdMost, long instanceIdLeast) {
        claims.remove(new FlowKey(instanceIdMost, instanceIdLeast));
    }
}
