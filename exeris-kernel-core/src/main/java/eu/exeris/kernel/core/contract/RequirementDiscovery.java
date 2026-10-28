/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Discovers the {@link EntitlementRequirement}s on the classpath for {@link ContractBootstrapStep} and holds
 * each one to its contract, so a broken provider stops the boot with {@code EX-LIC-0008} instead of escaping
 * as a raw error or silently declaring nothing.
 */
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CyclomaticComplexity"})
// a provider's own failure is turned into EX-LIC-0008; each check of its contract is one branch
final class RequirementDiscovery {

    private RequirementDiscovery() {}

    /**
     * What the classpath's requirements declare: each capability identifier with its entitlement class,
     * and the provider that declared it.
     *
     * @param byId         class of every declared identifier, sorted by identifier
     * @param providerById name of the provider that declared each identifier; the first one when two
     *                     providers declare the same identifier under the same class
     */
    /* default */ value record DeclaredRequirements(
            SortedMap<String, EntitlementClass> byId, SortedMap<String, String> providerById) {

        /**
         * Reports whether no requirement is declared.
         *
         * @return {@code true} when no identifier is declared
         */
        /* default */ boolean isEmpty() {
            return byId.isEmpty();
        }
    }

    /**
     * Loads every {@link EntitlementRequirement} and checks it against its contract, one provider at a time.
     *
     * @param classLoader loader to discover providers with
     * @return the declared identifiers with their classes, sorted
     * @throws ContractBreachException with EX-LIC-0008 naming the provider that breaks the contract, or the
     *                                 second provider when two declare one identifier under different classes
     */
    /* default */ static DeclaredRequirements declared(ClassLoader classLoader) {
        SortedMap<String, EntitlementClass> byId = new TreeMap<>();
        SortedMap<String, String> providerById = new TreeMap<>();
        Iterator<ServiceLoader.Provider<EntitlementRequirement>> providers =
                ServiceLoader.load(EntitlementRequirement.class, classLoader).stream().iterator();
        ServiceLoader.Provider<EntitlementRequirement> provider = next(providers);
        while (provider != null) {
            String name = provider.type().getName();
            EntitlementRequirement requirement = instantiate(provider);
            EntitlementClass entitlementClass = classOf(name, requirement);
            for (String capability : capabilitiesOf(name, requirement)) {
                EntitlementClass earlier = byId.putIfAbsent(capability, entitlementClass);
                if (earlier == null) {
                    providerById.put(capability, name);
                } else if (earlier != entitlementClass) {
                    throw invalid(name, "declares '" + capability + "' as " + entitlementClass.id()
                            + ", which " + providerById.get(capability) + " declares as " + earlier.id(), null);
                }
            }
            provider = next(providers);
        }
        return new DeclaredRequirements(byId, providerById);
    }

    private static ServiceLoader.Provider<EntitlementRequirement> next(
            Iterator<ServiceLoader.Provider<EntitlementRequirement>> providers) {
        try {
            return providers.hasNext() ? providers.next() : null;
        } catch (ServiceConfigurationError e) {
            throw invalid(EntitlementRequirement.class.getName(), "cannot be loaded", e);
        }
    }

    private static EntitlementRequirement instantiate(ServiceLoader.Provider<EntitlementRequirement> provider) {
        try {
            return provider.get();
        } catch (ServiceConfigurationError | RuntimeException e) {
            throw invalid(provider.type().getName(), "failed to declare its capabilities", e);
        }
    }

    private static EntitlementClass classOf(String name, EntitlementRequirement requirement) {
        EntitlementClass entitlementClass;
        try {
            entitlementClass = requirement.entitlementClass();
        } catch (ServiceConfigurationError | RuntimeException e) {
            throw invalid(name, "failed to declare its entitlement class", e);
        }
        if (entitlementClass == null) {
            throw invalid(name, "declares no entitlement class", null);
        }
        return entitlementClass;
    }

    private static Set<String> capabilitiesOf(String name, EntitlementRequirement requirement) {
        Set<String> declared;
        try {
            declared = requirement.requiredCapabilities();
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
