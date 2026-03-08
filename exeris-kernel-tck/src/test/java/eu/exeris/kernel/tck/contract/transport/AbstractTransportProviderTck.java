/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.transport.TransportProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link TransportProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerName()} is non-null and non-blank</li>
 *   <li>{@code priority()} follows Open-Core convention (0 or 100)</li>
 *   <li>Provider is discoverable via {@link ServiceLoader}</li>
 *   <li>Highest-priority provider wins selection</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractTransportProviderTck {

    /**
     * Creates the {@link TransportProvider} under test.
     */
    protected abstract TransportProvider createProvider();

    private TransportProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerName() is non-blank")
        void providerNameNonBlank() {
            assertThat(provider.providerName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("providerName() is stable across calls")
        void providerNameStable() {
            assertThat(provider.providerName()).isEqualTo(provider.providerName());
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
        @DisplayName("TransportProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(TransportProvider.class).stream().count();
            assertThat(count)
                    .as("At least one TransportProvider must be on the classpath")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            TransportProvider selected = ServiceLoader.load(TransportProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(TransportProvider::priority))
                    .orElseThrow();
            assertThat(selected.priority()).isGreaterThanOrEqualTo(provider.priority());
        }
    }
}
