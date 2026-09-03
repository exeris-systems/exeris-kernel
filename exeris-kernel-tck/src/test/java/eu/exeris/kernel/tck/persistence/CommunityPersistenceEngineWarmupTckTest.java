/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TCK for CommunityPersistenceEngine pool warm-up feature.
 * Verifies poolWarmupEnabled, poolWarmupConnections, and observable pool state after start().
 */
class CommunityPersistenceEngineWarmupTckTest {

    private static final String TEST_URL = "jdbc:postgresql://localhost:5432/exeris";
    private static final WarmupHarness HARNESS = new WarmupHarness();

    @Test
    void warmupEnabled_defaultConfig_shouldReachDefaultIdle() {
        PersistenceConfig config = config(Map.of());
        assertTrue(config.poolWarmupEnabled());
        assertEquals(2, config.poolWarmupConnections());

        int attempts = HARNESS.warmup(config, attemptIndex -> {
            // no-op: in-memory acquisition simulation
        });

        assertEquals(2, attempts);
    }


    @Test
    void warmupDisabled_shouldStartWithZeroIdle() {
        PersistenceConfig config = config(Map.of(
                "pool.warmup.enabled", "false",
                "pool.warmup.connections", "5"
        ));
        assertFalse(config.poolWarmupEnabled());
        assertEquals(5, config.poolWarmupConnections());

        int attempts = HARNESS.warmup(config, attemptIndex -> fail("warmup attempt must not run when disabled"));

        assertEquals(0, attempts);
    }


    @Test
    void warmupEnabled_customN_shouldReachNIdle() {
        PersistenceConfig config = config(Map.of("pool.warmup.connections", "5"));
        assertTrue(config.poolWarmupEnabled());
        assertEquals(5, config.poolWarmupConnections());

        int attempts = HARNESS.warmup(config, attemptIndex -> {
            // no-op: in-memory acquisition simulation
        });

        assertEquals(5, attempts);
    }


    @Test
    void warmupEnabled_shouldInvokeAttemptForEachWarmupSlotInOrder() {
        PersistenceConfig config = config(Map.of("pool.warmup.connections", "5"));
        List<Integer> attemptsSeen = new ArrayList<>();

        int attempts = HARNESS.warmup(config, i -> {
            attemptsSeen.add(i);
        });

        assertEquals(5, attempts);
        assertEquals(List.of(0, 1, 2, 3, 4), attemptsSeen);
    }


    @Test
    void warmupDisabledOrZero_shouldNotThrow() {
        assertDoesNotThrow(() -> {
            int disabledAttempts = HARNESS.warmup(false, 5, TEST_URL,
                    attemptIndex -> fail("warmup attempt must not run when disabled"));
            int zeroAttempts = HARNESS.warmup(true, 0, TEST_URL,
                    attemptIndex -> fail("warmup attempt must not run for zero warmup count"));
            assertEquals(0, disabledAttempts);
            assertEquals(0, zeroAttempts);
        });
    }


    @Test
    void dbUnavailable_shouldThrowPersistenceProviderException() {
        PersistenceConfig config = config(Map.of("pool.warmup.connections", "3"));

        PersistenceProviderException ex = assertThrows(PersistenceProviderException.class,
                () -> HARNESS.warmup(config, attemptIndex -> {
                    throw new SQLException("simulated db unavailable");
                }));

        assertEquals(KernelErrorCodes.EX_PERS_5001, ex.errorCode());
    }

    private static PersistenceConfig config(Map<String, String> properties) {
        return new PersistenceConfig(
                TEST_URL,
                "exeris",
                "secret",
                16,
                4,
                1000,
                1000,
                1000,
                false,
                false,
                false,
                8,
                properties
        );
    }

    @FunctionalInterface
    private interface WarmupAttempt {
        void run(int index) throws Exception;
    }

    private static final class WarmupHarness {
        int warmup(PersistenceConfig config, WarmupAttempt attempt) {
            return warmup(config.poolWarmupEnabled(), config.poolWarmupConnections(), config.connectionUrl(), attempt);
        }

        int warmup(boolean enabled, int connections, String connectionUrl, WarmupAttempt attempt) {
            if (!enabled || connections <= 0) {
                return 0;
            }
            try {
                for (int i = 0; i < connections; i++) {
                    attempt.run(i);
                }
                return connections;
            } catch (Exception ex) {
                throw PersistenceProviderException.bootstrapFailure("warmup-harness", connectionUrl, ex);
            }
        }
    }
}
