/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link GraphProvider} contract verification.
 *
 * @since 0.5.0
 */
public abstract class AbstractGraphProviderTck {

    protected abstract GraphProvider createProvider();

    private GraphProvider provider;

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
        @DisplayName("priority() follows Open-Core convention")
        void priorityConvention() {
            assertThat(provider.priority()).isIn(0, 100);
        }
    }

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderContract {

        @Test
        @DisplayName("GraphProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(GraphProvider.class).stream().count();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            GraphProvider selected = ServiceLoader.load(GraphProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(GraphProvider::priority))
                    .orElseThrow();
            assertThat(selected.priority()).isGreaterThanOrEqualTo(provider.priority());
        }
    }
}

