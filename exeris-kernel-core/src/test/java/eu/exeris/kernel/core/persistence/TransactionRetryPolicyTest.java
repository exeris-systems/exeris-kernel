/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link TransactionRetryPolicy} — delay computation and factory semantics.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>{@link TransactionRetryPolicy#NONE} — single attempt, no delay</li>
 *   <li>{@link TransactionRetryPolicy#exponential} factory — correct multiplier</li>
 *   <li>{@link TransactionRetryPolicy#delayFor} — delay capped at 30 s, first attempt = 0</li>
 *   <li>Valhalla-readiness: record is identity-free, fields immutable</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1: TransactionRetryPolicy — delay math and factory semantics")
class TransactionRetryPolicyTest {

    @Nested
    @DisplayName("NONE constant")
    class NoneConstant {

        @Test
        @DisplayName("NONE has maxAttempts=1")
        void noneHasOneAttempt() {
            assertThat(TransactionRetryPolicy.NONE.maxAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("NONE has baseDelayMs=0")
        void noneHasZeroDelay() {
            assertThat(TransactionRetryPolicy.NONE.baseDelayMs()).isZero();
        }

        @Test
        @DisplayName("NONE: delayFor(0) returns 0")
        void noneDelayForFirstAttempt() {
            assertThat(TransactionRetryPolicy.NONE.delayFor(0)).isZero();
        }

        @Test
        @DisplayName("NONE: delayFor(1) returns 0 (no delay because baseDelayMs=0)")
        void noneDelayForSecondAttempt() {
            assertThat(TransactionRetryPolicy.NONE.delayFor(1)).isZero();
        }
    }

    @Nested
    @DisplayName("exponential() factory")
    class ExponentialFactory {

        @Test
        @DisplayName("exponential(3, 50) creates policy with correct maxAttempts, baseDelayMs and backoffMultiplier")
        void correctFields() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(3, 50L);
            assertThat(policy.maxAttempts()).isEqualTo(3);
            assertThat(policy.baseDelayMs()).isEqualTo(50L);
            assertThat(policy.backoffMultiplier()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("delayFor() — exponential back-off computation")
    class DelayForComputation {

        @Test
        @DisplayName("delayFor(0) always returns 0 — first attempt has no pre-delay")
        void firstAttemptNoDelay() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(5, 100L);
            assertThat(policy.delayFor(0)).isZero();
        }

        @Test
        @DisplayName("delayFor(1) returns baseDelayMs (100 ms)")
        void secondAttemptBaseDelay() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(5, 100L);
            // attempt=1: 100 * 2^0 = 100
            assertThat(policy.delayFor(1)).isEqualTo(100L);
        }

        @Test
        @DisplayName("delayFor(2) returns baseDelayMs * 2 (200 ms)")
        void thirdAttemptDoubleDelay() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(5, 100L);
            // attempt=2: 100 * 2^1 = 200
            assertThat(policy.delayFor(2)).isEqualTo(200L);
        }

        @Test
        @DisplayName("delayFor(3) returns baseDelayMs * 4 (400 ms)")
        void fourthAttemptQuadDelay() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(5, 100L);
            // attempt=3: 100 * 2^2 = 400
            assertThat(policy.delayFor(3)).isEqualTo(400L);
        }

        @Test
        @DisplayName("delayFor caps at 30 000 ms (30 seconds)")
        void delayCapsAt30Seconds() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(100, 1_000L);
            // Very large attempt number → delay would exceed 30 s → capped
            long delay = policy.delayFor(99);
            assertThat(delay).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("negative baseDelayMs is rejected by the canonical constructor")
        void negativeBaseDelayRejected() {
            assertThatThrownBy(() -> new TransactionRetryPolicy(3, -1L, 2.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseDelayMs must be >= 0");
        }
    }

    @Nested
    @DisplayName("Valhalla-readiness — identity-free record")
    class ValhallaReadiness {

        @Test
        @DisplayName("Two equal policies are equal by value — no identity dependency")
        void equalByValue() {
            TransactionRetryPolicy p1 = TransactionRetryPolicy.exponential(3, 50L);
            TransactionRetryPolicy p2 = TransactionRetryPolicy.exponential(3, 50L);
            assertThat(p1).isEqualTo(p2).hasSameHashCodeAs(p2);
        }

        @Test
        @DisplayName("toString() contains all policy fields (record default)")
        void toStringContainsFields() {
            TransactionRetryPolicy policy = TransactionRetryPolicy.exponential(3, 50L);
            String str = policy.toString();
            assertThat(str).contains("3", "50", "2.0");
        }
    }
}
