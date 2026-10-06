/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import java.util.Set;

/**
 * Declares that an artifact on the runtime classpath needs a commercial entitlement to run in a
 * production environment.
 *
 * <p>Every artifact whose production execution requires an entitlement registers one
 * implementation in {@code META-INF/services/eu.exeris.kernel.spi.contract.EntitlementRequirement}.
 * Community artifacts register none: the Community edition is unrestricted, so a kernel with no
 * requirement on its classpath boots in every environment without a license manifest.
 *
 * <p>The kernel's contract gate discovers the requirements before any subsystem is initialized. In an
 * environment where {@link ExecutionEnvironment#requiresEntitlement()} holds, a declared requirement with
 * no license manifest stops the boot, and with a manifest each declared capability is enforced through
 * {@link ExecutionContract#assertCapability(String)}. In any other environment a declared requirement with
 * no manifest is reported as a {@code SOFT} warning, because entitlement-requiring code then runs
 * ungated.
 *
 * @implSpec Implementations are discovered through {@code ServiceLoader} and must declare a public
 *           no-argument constructor. {@link #requiredCapabilities()} must return the same non-empty set
 *           on every call, every element must satisfy {@link ExecutionContract#isCapabilityId(String)},
 *           and it must not touch any kernel service: it runs before any is bound. A provider that cannot
 *           be loaded, throws, or returns {@code null}, an empty set or a malformed identifier stops the
 *           boot with {@code EX-LIC-0008} in every environment.
 * @since 0.13
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface") // a ServiceLoader provider, not a lambda target
public interface EntitlementRequirement {

    /**
     * Returns the capabilities this artifact needs to be entitled to.
     *
     * @return non-empty set of capability identifiers matching {@link ExecutionContract#CAPABILITY_ID_PATTERN}
     */
    Set<String> requiredCapabilities();
}
