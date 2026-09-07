/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link PersistenceProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerId()} is non-null and non-blank</li>
 *   <li>{@code providerName()} is non-null and non-blank</li>
 *   <li>{@code priority()} follows Open-Core convention (0 or 100)</li>
 *   <li>Provider is discoverable via {@link ServiceLoader}</li>
 * </ul>
 *
 * @since 0.5
 */
public abstract class AbstractPersistenceProviderTck {

    /**
     * Creates the {@link PersistenceProvider} under test.
     */
    protected abstract PersistenceProvider createProvider();

    private PersistenceProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerId() is non-blank")
        void providerIdNonBlank() {
            assertThat(provider.providerId()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("providerName() is non-blank")
        void providerNameNonBlank() {
            assertThat(provider.providerName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("providerId() is stable across calls")
        void providerIdStable() {
            assertThat(provider.providerId()).isEqualTo(provider.providerId());
        }

        @Test
        @DisplayName("priority() follows Open-Core convention")
        void priorityConvention() {
            assertThat(provider.priority()).isIn(0, 100);
        }
    }

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderContract {

        @Test
        @DisplayName("PersistenceProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(PersistenceProvider.class).stream().count();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            PersistenceProvider selected = ServiceLoader.load(PersistenceProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(PersistenceProvider::priority))
                    .orElseThrow();
            assertThat(selected.priority()).isGreaterThanOrEqualTo(provider.priority());
        }
    }
}
