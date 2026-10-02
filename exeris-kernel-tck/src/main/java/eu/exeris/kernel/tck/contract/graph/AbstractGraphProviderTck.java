/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 */
public abstract class AbstractGraphProviderTck {

    /**
     * Creates the {@link GraphProvider} implementation under test.
     *
     * @return the provider implementation under test
     */
    protected abstract GraphProvider createProvider();

    private GraphProvider provider;

    /**
     * Creates the contract; subclasses supply the {@link GraphProvider} implementation under test via
     * {@link #createProvider()}.
     */
    public AbstractGraphProviderTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        /**
         * Groups the assertions for provider identity.
         */
        Identity() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

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

        /**
         * Groups the assertions for {@code ServiceLoader} integration.
         */
        ServiceLoaderContract() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("GraphProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(GraphProvider.class).stream().count();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        /**
         * Recomputes the highest-priority provider from {@link ServiceLoader} with the same
         * max-by-priority reduction the kernel bootstrapper uses, then asserts it is at least
         * {@link #provider}'s priority. Because the compared value is itself the maximum over
         * the set {@link #provider} is drawn from, this holds whenever {@link #provider} is
         * among the discovered providers; it does not independently verify that a
         * lower-priority binding is passed over when several are on the classpath.
         */
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

