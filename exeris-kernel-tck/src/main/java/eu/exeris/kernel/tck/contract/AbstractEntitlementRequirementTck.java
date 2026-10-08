/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK conformance of an {@link EntitlementRequirement} provider (ADR-089, amendment of 2026-10-06).
 *
 * <p>The kernel's contract gate loads every provider through {@code ServiceLoader} before any kernel service
 * is bound, and refuses the boot with {@code EX-LIC-0008} when one breaks its contract. A binding names its
 * provider class; this suite checks what the gate relies on: a public no-argument constructor, the same
 * non-empty set on every call and from every instance, identifiers of capability shape, and no use of
 * {@link KernelProviders} bindings.
 *
 * @since 0.13
 */
public abstract class AbstractEntitlementRequirementTck {

    /** Constructor for subclasses. */
    protected AbstractEntitlementRequirementTck() {
        // binding classes name the provider under test
    }

    /**
     * Returns the provider class the artifact registers in
     * {@code META-INF/services/eu.exeris.kernel.spi.contract.EntitlementRequirement}.
     *
     * @return provider class under test
     */
    protected abstract Class<? extends EntitlementRequirement> providerClass();

    private EntitlementRequirement newProvider() throws Exception {
        return providerClass().getConstructor().newInstance();
    }

    @Test
    @DisplayName("The provider is a public, concrete class with a public no-argument constructor")
    void serviceLoaderCanInstantiate() throws Exception {
        Class<? extends EntitlementRequirement> type = providerClass();
        assertThat(Modifier.isPublic(type.getModifiers())).isTrue();
        assertThat(Modifier.isAbstract(type.getModifiers())).isFalse();
        Constructor<? extends EntitlementRequirement> constructor = type.getConstructor();
        assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("It declares a non-empty set, the same on every call and from every instance")
    void declarationIsStable() throws Exception {
        EntitlementRequirement provider = newProvider();
        Set<String> first = provider.requiredCapabilities();

        assertThat(first).isNotNull().isNotEmpty();
        assertThat(provider.requiredCapabilities()).isEqualTo(first);
        assertThat(newProvider().requiredCapabilities()).isEqualTo(first);
    }

    @Test
    @DisplayName("Every declared identifier has capability shape, so a manifest can entitle it")
    void identifiersHaveCapabilityShape() throws Exception {
        for (String capability : newProvider().requiredCapabilities()) {
            assertThat(ExecutionContract.isCapabilityId(capability)).as("%s", capability).isTrue();
        }
    }

    @Test
    @DisplayName("It answers with no kernel service bound, as it must before the kernel scope exists")
    void needsNoKernelService() throws Exception {
        assertThat(KernelProviders.CURRENT_CONFIG.isBound()).isFalse();
        assertThat(ContractKernelProviders.EXECUTION_CONTRACT.isBound()).isFalse();

        assertThat(newProvider().requiredCapabilities()).isNotEmpty();
    }
}
