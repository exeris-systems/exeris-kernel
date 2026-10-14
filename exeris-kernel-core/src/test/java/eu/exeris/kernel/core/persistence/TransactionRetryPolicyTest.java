/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 *   <li>{@link TransactionRetryPolicy#fromConfig} — configuration keys and their defaults</li>
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

    @Nested
    @DisplayName("fromConfig() resolution")
    class FromConfig {

        @Test
        @DisplayName("no retry keys configured resolves to NONE")
        void unconfiguredIsNone() {
            assertThat(TransactionRetryPolicy.fromConfig(config(Map.of(), Map.of())))
                    .isSameAs(TransactionRetryPolicy.NONE);
        }

        @Test
        @DisplayName("maxAttempts=1 resolves to NONE even when a delay is configured")
        void singleAttemptIgnoresDelay() {
            ConfigProvider config = config(
                    Map.of(TransactionRetryPolicy.MAX_ATTEMPTS_KEY, 1),
                    Map.of(TransactionRetryPolicy.BASE_DELAY_MS_KEY, 200L));

            assertThat(TransactionRetryPolicy.fromConfig(config)).isSameAs(TransactionRetryPolicy.NONE);
        }

        @Test
        @DisplayName("maxAttempts>1 resolves to exponential back-off with the configured delay")
        void multipleAttemptsUseConfiguredDelay() {
            ConfigProvider config = config(
                    Map.of(TransactionRetryPolicy.MAX_ATTEMPTS_KEY, 4),
                    Map.of(TransactionRetryPolicy.BASE_DELAY_MS_KEY, 200L));

            assertThat(TransactionRetryPolicy.fromConfig(config))
                    .isEqualTo(TransactionRetryPolicy.exponential(4, 200L));
        }

        @Test
        @DisplayName("maxAttempts>1 without a delay key starts at the default delay")
        void multipleAttemptsDefaultDelay() {
            ConfigProvider config = config(Map.of(TransactionRetryPolicy.MAX_ATTEMPTS_KEY, 3), Map.of());

            assertThat(TransactionRetryPolicy.fromConfig(config))
                    .isEqualTo(TransactionRetryPolicy.exponential(3, TransactionRetryPolicy.DEFAULT_BASE_DELAY_MS));
        }

        @Test
        @DisplayName("maxAttempts=0 is rejected rather than silently disabling the executor")
        void zeroAttemptsRejected() {
            ConfigProvider config = config(Map.of(TransactionRetryPolicy.MAX_ATTEMPTS_KEY, 0), Map.of());

            assertThatThrownBy(() -> TransactionRetryPolicy.fromConfig(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts must be >= 1");
        }

        private static ConfigProvider config(Map<String, Integer> ints, Map<String, Long> longs) {
            return new ConfigProvider() {
                @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
                @Override public Optional<String> getString(String key) { return Optional.empty(); }
                @Override public Optional<Integer> getInt(String key) { return Optional.ofNullable(ints.get(key)); }
                @Override public Optional<Long> getLong(String key) { return Optional.ofNullable(longs.get(key)); }
                @Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
                @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
                @Override public void watch(String file, String key, Consumer<Object> callback) {
                    // no hot reload in this fixture
                }
            };
        }
    }
}
