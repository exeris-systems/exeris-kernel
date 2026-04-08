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
 * <p>Uses a {@link ConcurrentHashMap} keyed by {@code (instanceIdMost, instanceIdLeast, stepIndex)}.
 * {@code putIfAbsent} semantics guarantee exactly-once step execution across concurrent claims.
 * If the engine terminates before a flow reaches a terminal state, entries are silently
 * discarded — acceptable for the Community tier (no off-heap leak).
 *
 * @since 0.6.0
 */
final class CoreIdempotencyGuard implements IdempotencyGuard {

    private final ConcurrentMap<StepKey, Boolean> claimed = new ConcurrentHashMap<>();

    private record StepKey(long instanceIdMost, long instanceIdLeast, int stepIndex) {}

    @Override
    public boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex) {
        return claimed.putIfAbsent(
                new StepKey(instanceIdMost, instanceIdLeast, stepIndex), Boolean.TRUE) == null;
    }

    @Override
    public void releaseInstance(long instanceIdMost, long instanceIdLeast) {
        claimed.keySet().removeIf(k -> k.instanceIdMost() == instanceIdMost
                                    && k.instanceIdLeast() == instanceIdLeast);
    }
}
