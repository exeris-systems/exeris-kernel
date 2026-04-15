/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.IdempotencyGuard;
import eu.exeris.kernel.tck.contract.flow.AbstractIdempotencyGuardTck;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@DisplayName("Community: Flow IdempotencyGuard TCK")
class CommunityFlowIdempotencyGuardTckTest extends AbstractIdempotencyGuardTck {

    @Override
    protected IdempotencyGuard createGuard() {
        return new CommunityGuardBinding();
    }

    private static final class CommunityGuardBinding implements IdempotencyGuard {

        private final ConcurrentMap<InstanceKey, ConcurrentMap<Integer, Boolean>> claims = new ConcurrentHashMap<>();

        @Override
        public boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex) {
            InstanceKey key = new InstanceKey(instanceIdMost, instanceIdLeast);
            ConcurrentMap<Integer, Boolean> steps = claims.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
            return steps.putIfAbsent(stepIndex, Boolean.TRUE) == null;
        }

        @Override
        public void releaseInstance(long instanceIdMost, long instanceIdLeast) {
            claims.remove(new InstanceKey(instanceIdMost, instanceIdLeast));
        }

        private record InstanceKey(long instanceIdMost, long instanceIdLeast) {
        }
    }
}
