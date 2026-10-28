/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.tck.support.PriorityRivals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
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
     *
     * @return a provider under test
     */
    protected abstract PersistenceProvider createProvider();

    /**
     * Runs the selection the binding's kernel applies to discovered {@code PersistenceProvider}s over exactly
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
    protected PersistenceProvider selectAmong(List<PersistenceProvider> candidates) {
        throw new UnsupportedOperationException(
                "Override selectAmong to route the candidates through the kernel's provider selection");
    }

    private PersistenceProvider provider;

    /**
     * Creates the contract; subclasses supply the provider via {@link #createProvider()}.
     */
    public AbstractPersistenceProviderTck() {
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
         * Groups the {@code providerId()}/{@code providerName()}/{@code priority()} identity
         * assertions.
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

        /**
         * Groups the {@link ServiceLoader} discoverability and priority-ordering assertions.
         */
        ServiceLoaderContract() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("PersistenceProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(PersistenceProvider.class).stream().count();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        /**
         * Stages a rival one priority step above and one below the provider under test, in both
         * discovery orders, and requires the binding's selection to return the highest by identity.
         */
        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            PersistenceProvider higher = PriorityRivals.rival(
                    PersistenceProvider.class, Math.addExact(provider.priority(), 1), "tck-higher-priority");
            PersistenceProvider lower = PriorityRivals.rival(
                    PersistenceProvider.class, Math.subtractExact(provider.priority(), 1), "tck-lower-priority");

            assertThat(selectAmong(List.of(lower, provider, higher))).as("higher-priority rival listed last").isSameAs(higher);
            assertThat(selectAmong(List.of(higher, provider, lower))).as("higher-priority rival listed first").isSameAs(higher);
            assertThat(selectAmong(List.of(lower, provider))).as("provider under test listed last").isSameAs(provider);
            assertThat(selectAmong(List.of(provider, lower))).as("provider under test listed first").isSameAs(provider);
        }
    }
}
