/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityPersistenceProvider} — contract verification without a
 * live database connection.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>providerId() and providerName() are stable identifiers</li>
 *   <li>priority() == 0 (Community Open-Core slot)</li>
 *   <li>createEngine() with invalid JDBC URL wraps in PersistenceProviderException.bootstrapFailure</li>
 *   <li>createEngine() with a valid URL returns a usable engine instance</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityPersistenceProvider")
class CommunityPersistenceProviderTest {

    private final PersistenceProvider provider = new CommunityPersistenceProvider();

    // =========================================================================
    // Provider identity
    // =========================================================================

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerId() is 'postgres-community'")
        void providerIdIsStable() {
            assertThat(provider.providerId()).isEqualTo("postgres-community");
        }

        @Test
        @DisplayName("providerName() contains 'Community'")
        void providerNameContainsCommunity() {
            assertThat(provider.providerName()).containsIgnoringCase("Community");
        }

        @Test
        @DisplayName("priority() == 0 (Community Open-Core slot)")
        void priorityIsZero() {
            assertThat(provider.priority()).isZero();
        }

        @Test
        @DisplayName("providerId() is stable across multiple calls")
        void providerIdIsStableAcrossCalls() {
            assertThat(provider.providerId()).isEqualTo(provider.providerId());
        }
    }

    // =========================================================================
    // Engine creation — error handling
    // =========================================================================

    @Nested
    @DisplayName("Engine creation")
    class EngineCreation {

        @Test
        @DisplayName("createEngine() with invalid JDBC URL throws PersistenceProviderException")
        void invalidJdbcUrlThrowsBootstrapFailure() {
            PersistenceConfig config = PersistenceConfig.defaults(
                    "jdbc:invalid://nonexistent-host:9999/db", "user", "pass");

            // HikariCP detects invalid driver immediately
            assertThatThrownBy(() -> provider.createEngine(config))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("createEngine() with valid H2 in-memory URL returns non-null engine")
        void validH2UrlReturnsEngine() {
            PersistenceConfig config = PersistenceConfig.defaults(
                    "jdbc:h2:mem:exeris_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                    "sa", "");

            try (PersistenceEngine engine = provider.createEngine(config)) {
                assertThat(engine).isNotNull();
                assertThat(engine.canServiceRequest()).isTrue();
            }
        }
    }
}



