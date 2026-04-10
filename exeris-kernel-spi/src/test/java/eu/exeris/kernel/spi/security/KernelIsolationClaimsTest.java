/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0: Verifies {@link KernelIsolationClaims} constant values and uniqueness.
 *
 * @since 0.5.0
 */
@DisplayName("L0: KernelIsolationClaims — constant values and uniqueness")
class KernelIsolationClaimsTest {

    @Test
    @DisplayName("ISOLATION_STRATEGY has the expected claim name")
    void isolationStrategyClaimName() {
        assertThat(KernelIsolationClaims.ISOLATION_STRATEGY)
                .isEqualTo("x-exeris-isolation-strategy");
    }

    @Test
    @DisplayName("SCHEMA_NAME has the expected claim name")
    void schemaNameClaimName() {
        assertThat(KernelIsolationClaims.SCHEMA_NAME)
                .isEqualTo("x-exeris-isolation-schema");
    }

    @Test
    @DisplayName("DATASOURCE_KEY has the expected claim name")
    void datasourceKeyClaimName() {
        assertThat(KernelIsolationClaims.DATASOURCE_KEY)
                .isEqualTo("x-exeris-isolation-datasource");
    }

    @Test
    @DisplayName("All three constants have distinct values")
    void allConstantsAreDistinct() {
        Set<String> values = Set.of(
                KernelIsolationClaims.ISOLATION_STRATEGY,
                KernelIsolationClaims.SCHEMA_NAME,
                KernelIsolationClaims.DATASOURCE_KEY);
        assertThat(values).hasSize(3);
    }
}
