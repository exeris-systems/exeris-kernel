/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.tck.support.PriorityRivals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /**
     * Runs the selection the binding's kernel applies to discovered {@code GraphProvider}s over exactly
     * {@code candidates}, and returns the provider that selection picks.
     *
     * <p>Delegate to the production selection routine, not to a reduction written in the binding:
     * the precedence contract is judged against whatever this method returns.
     *
     * @param candidates the providers to choose between, in discovery order
     * @return the selected provider
     * @throws UnsupportedOperationException by default, so a binding that does not route the candidates
     *         through its kernel's selection fails the precedence contract rather than skipping it
     */
    protected GraphProvider selectAmong(List<GraphProvider> candidates) {
        throw new UnsupportedOperationException(
                "Override selectAmong to route the candidates through the kernel's provider selection");
    }

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
         * Stages a rival one priority step above and one below the provider under test, in both
         * discovery orders, and requires the binding's selection to return the highest by identity.
         */
        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            GraphProvider higher = PriorityRivals.rival(GraphProvider.class, provider.priority() + 1, "tck-higher-priority");
            GraphProvider lower = PriorityRivals.rival(GraphProvider.class, provider.priority() - 1, "tck-lower-priority");

            assertThat(selectAmong(List.of(lower, provider, higher))).as("higher-priority rival listed last").isSameAs(higher);
            assertThat(selectAmong(List.of(higher, provider, lower))).as("higher-priority rival listed first").isSameAs(higher);
            assertThat(selectAmong(List.of(lower, provider))).as("provider under test listed last").isSameAs(provider);
            assertThat(selectAmong(List.of(provider, lower))).as("provider under test listed first").isSameAs(provider);
        }
    }
}

