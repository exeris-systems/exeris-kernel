/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link PersistenceConfig#dedicatedDataSources()} — construction, immutability,
 * nesting guard, and backward-compatible 13-arg constructor.
 *
 * @since 0.5.0
 */
@DisplayName("L0: PersistenceConfig — dedicatedDataSources contract")
class PersistenceConfigDedicatedTest {

    private static PersistenceConfig baseConfig(Map<String, PersistenceConfig> dedicated) {
        return new PersistenceConfig(
                "jdbc:h2:mem:test;MODE=PostgreSQL",
                "sa", "",
                4, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 16,
                Map.of(),
                dedicated);
    }

    private static PersistenceConfig leafConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:dedicated_leaf;MODE=PostgreSQL",
                "sa", "",
                2, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 16,
                Map.of());
    }

    // =========================================================================
    // Empty dedicated map (default)
    // =========================================================================

    @Test
    @DisplayName("13-arg constructor sets dedicatedDataSources to empty map")
    void thirteenArgConstructorDefaultsToEmptyDedicatedMap() {
        PersistenceConfig config = new PersistenceConfig(
                "jdbc:h2:mem:test", "sa", "",
                4, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 16, Map.of());
        assertThat(config.dedicatedDataSources()).isEmpty();
    }

    // =========================================================================
    // Non-empty dedicated map accepted
    // =========================================================================

    @Test
    @DisplayName("Non-empty dedicatedDataSources is accepted and accessible")
    void nonEmptyDedicatedMapIsAccepted() {
        PersistenceConfig leaf = leafConfig();
        PersistenceConfig config = baseConfig(Map.of("ds-primary", leaf));

        assertThat(config.dedicatedDataSources()).containsKey("ds-primary");
        assertThat(config.dedicatedDataSources().get("ds-primary").connectionUrl())
                .isEqualTo("jdbc:h2:mem:dedicated_leaf;MODE=PostgreSQL");
    }

    // =========================================================================
    // Immutability — defensive copy
    // =========================================================================

    @Test
    @DisplayName("dedicatedDataSources is immutable after construction — mutation of source map has no effect")
    void dedicatedMapIsDefensivelyCopied() {
        PersistenceConfig leaf = leafConfig();
        Map<String, PersistenceConfig> mutable = new HashMap<>();
        mutable.put("ds-a", leaf);

        PersistenceConfig config = baseConfig(mutable);
        mutable.put("ds-b", leaf);  // post-construction mutation

        assertThat(config.dedicatedDataSources()).containsOnlyKeys("ds-a");
    }

    @Test
    @DisplayName("dedicatedDataSources() returns unmodifiable map")
    void dedicatedMapIsUnmodifiable() {
        PersistenceConfig config = baseConfig(Map.of("ds-a", leafConfig()));
        assertThatThrownBy(() -> config.dedicatedDataSources().put("ds-x", leafConfig()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // =========================================================================
    // Nesting guard — depth = 1 only
    // =========================================================================

    @Test
    @DisplayName("Nested config with an empty dedicatedDataSources is accepted")
    void nestedConfigWithEmptyDedicatedIsAccepted() {
        // leaf has Map.of() — valid
        PersistenceConfig config = baseConfig(Map.of("ds-primary", leafConfig()));
        assertThat(config.dedicatedDataSources()).hasSize(1);
    }

    @Test
    @DisplayName("Nesting guard: nested config with non-empty dedicatedDataSources throws IllegalArgumentException")
    void nestingGuardRejectsDeepNesting() {
        // leaf itself has a non-empty dedicatedDataSources — depth = 2, must be rejected
        PersistenceConfig innerLeaf = leafConfig();
        PersistenceConfig nestedWithDedicated = new PersistenceConfig(
                "jdbc:h2:mem:nested", "sa", "",
                2, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 16,
                Map.of(),
                Map.of("inner", innerLeaf));  // this nested config has a non-empty dedicated map

        assertThatThrownBy(() -> baseConfig(Map.of("ds-bad", nestedWithDedicated)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain nested dedicatedDataSources")
                .hasMessageContaining("ds-bad");
    }

    // =========================================================================
    // Null guard
    // =========================================================================

    @Test
    @DisplayName("null dedicatedDataSources is rejected with NullPointerException")
    void nullDedicatedMapIsRejected() {
        assertThatThrownBy(() -> baseConfig(null))
                .isInstanceOf(NullPointerException.class);
    }
}
