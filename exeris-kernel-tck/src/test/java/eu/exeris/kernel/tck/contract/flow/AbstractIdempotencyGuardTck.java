/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.IdempotencyGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link IdempotencyGuard} contract verification.
 *
 * @since 0.5.0
 */
public abstract class AbstractIdempotencyGuardTck {

    protected abstract IdempotencyGuard createGuard();

    private IdempotencyGuard guard;

    private static final long MOST  = 0xABCDEF0123456789L;
    private static final long LEAST = 0x9876543210FEDCBAL;

    @BeforeEach
    final void setUp() {
        guard = createGuard();
    }

    @Test
    @DisplayName("tryClaimStep — first call returns true")
    void tryClaimStep_firstCall_returnsTrue() {
        assertThat(guard.tryClaimStep(MOST, LEAST, 0)).isTrue();
    }

    @Test
    @DisplayName("tryClaimStep — duplicate call for same (instance, step) returns false")
    void tryClaimStep_duplicateCall_returnsFalse() {
        guard.tryClaimStep(MOST, LEAST, 0);
        assertThat(guard.tryClaimStep(MOST, LEAST, 0)).isFalse();
    }

    @Test
    @DisplayName("tryClaimStep — different step indices on same instance are independent")
    void tryClaimStep_differentStep_sameInstance_returnsTrue() {
        guard.tryClaimStep(MOST, LEAST, 0);
        assertThat(guard.tryClaimStep(MOST, LEAST, 1))
                .as("Step 1 must be claimable independently of step 0")
                .isTrue();
    }

    @Test
    @Timeout(10)
    @DisplayName("tryClaimStep — under 16 concurrent VTs, exactly one wins per (instance, step)")
    void tryClaimStep_concurrentClaims_exactlyOneWins() throws InterruptedException {
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(0);
        List<Thread> vts = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            vts.add(Thread.ofVirtual().unstarted(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (guard.tryClaimStep(MOST, LEAST, 0)) {
                    wins.incrementAndGet();
                }
            }));
        }
        vts.forEach(Thread::start);
        assertThat(ready.await(5, TimeUnit.SECONDS))
                .as("All virtual threads must reach the barrier within 5 seconds")
                .isTrue();
        start.countDown();
        for (Thread vt : vts) {
            vt.join(java.time.Duration.ofSeconds(5));
            assertThat(vt.isAlive())
                    .as("Virtual thread must have terminated within 5 seconds")
                    .isFalse();
        }

        assertThat(wins.get())
                .as("Exactly one virtual thread must win the claim")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("releaseInstance — after release, tryClaimStep returns true again")
    void releaseInstance_allowsReclaim() {
        guard.tryClaimStep(MOST, LEAST, 0);
        guard.releaseInstance(MOST, LEAST);
        assertThat(guard.tryClaimStep(MOST, LEAST, 0))
                .as("After releaseInstance, step 0 must be claimable again")
                .isTrue();
    }
}
