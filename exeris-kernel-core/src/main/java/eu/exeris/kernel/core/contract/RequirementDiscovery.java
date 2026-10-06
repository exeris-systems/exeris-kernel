/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Discovers the {@link EntitlementRequirement}s on the classpath for {@link ContractBootstrapStep} and holds
 * each one to its contract, so a broken provider stops the boot with {@code EX-LIC-0008} instead of escaping
 * as a raw error or silently declaring nothing.
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException") // a provider's own failure is turned into EX-LIC-0008
final class RequirementDiscovery {

    private RequirementDiscovery() {}

    /**
     * Loads every {@link EntitlementRequirement} and checks it against its contract, one provider at a time.
     *
     * @param classLoader loader to discover providers with
     * @return the union of the declared capabilities, sorted
     * @throws ContractBreachException with EX-LIC-0008 naming the provider that breaks the contract
     */
    /* default */ static Set<String> requiredCapabilities(ClassLoader classLoader) {
        Set<String> required = new TreeSet<>();
        Iterator<ServiceLoader.Provider<EntitlementRequirement>> providers =
                ServiceLoader.load(EntitlementRequirement.class, classLoader).stream().iterator();
        ServiceLoader.Provider<EntitlementRequirement> provider = next(providers);
        while (provider != null) {
            required.addAll(declaredBy(provider));
            provider = next(providers);
        }
        return required;
    }

    private static ServiceLoader.Provider<EntitlementRequirement> next(
            Iterator<ServiceLoader.Provider<EntitlementRequirement>> providers) {
        try {
            return providers.hasNext() ? providers.next() : null;
        } catch (ServiceConfigurationError e) {
            throw invalid(EntitlementRequirement.class.getName(), "cannot be loaded", e);
        }
    }

    private static Set<String> declaredBy(ServiceLoader.Provider<EntitlementRequirement> provider) {
        String name = provider.type().getName();
        Set<String> declared;
        try {
            declared = provider.get().requiredCapabilities();
        } catch (ServiceConfigurationError | RuntimeException e) {
            throw invalid(name, "failed to declare its capabilities", e);
        }
        if (declared == null || declared.isEmpty()) {
            throw invalid(name, "declares no capability", null);
        }
        for (String capability : declared) {
            if (!ExecutionContract.isCapabilityId(capability)) {
                throw invalid(name, "declares a malformed capability identifier", null);
            }
        }
        return declared;
    }

    private static ContractBreachException invalid(String provider, String problem, Throwable cause) {
        String message = "EntitlementRequirement " + provider + " " + problem;
        return cause == null
                ? new ContractBreachException(KernelErrorCodes.EX_LIC_0008, message, provider)
                : new ContractBreachException(KernelErrorCodes.EX_LIC_0008, message, cause, provider);
    }
}
