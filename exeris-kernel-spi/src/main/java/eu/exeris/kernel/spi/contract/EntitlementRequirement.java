/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import java.util.Set;

/**
 * Declares that an artifact on the runtime classpath needs an entitlement to run, and of which
 * {@link EntitlementClass class} that entitlement is.
 *
 * <p>Every artifact whose execution requires an entitlement registers one
 * implementation in {@code META-INF/services/eu.exeris.kernel.spi.contract.EntitlementRequirement}.
 * Community artifacts register none: the Community edition is unrestricted, so a kernel with no
 * requirement on its classpath boots in every environment without a license manifest.
 *
 * <p>The kernel's contract gate discovers the requirements before any subsystem is initialized. A
 * declared requirement with no license manifest stops the boot in every environment, development
 * included. With a manifest, the key that signed it must be allowed to entitle the requirement's class,
 * and each declared capability is enforced through {@link ExecutionContract#assertCapability(String)}.
 *
 * @implSpec Implementations are discovered through {@code ServiceLoader} and must declare a public
 *           no-argument constructor. {@link #requiredCapabilities()} must return the same non-empty set
 *           on every call, every element must satisfy {@link ExecutionContract#isCapabilityId(String)},
 *           and it must not touch any kernel service: it runs before any is bound.
 *           {@link #entitlementClass()} must return the same non-null class on every call, and that class
 *           applies to every identifier {@link #requiredCapabilities()} returns; an artifact whose
 *           identifiers differ in class registers one provider per class. A provider that cannot be
 *           loaded, throws, or returns {@code null}, an empty set, a malformed identifier or a
 *           {@code null} class stops the boot with {@code EX-LIC-0008} in every environment, and so does
 *           one identifier declared under two classes.
 * @since 0.13
 */
public interface EntitlementRequirement {

    /**
     * Returns the capabilities this artifact needs to be entitled to.
     *
     * @return non-empty set of capability identifiers matching {@link ExecutionContract#CAPABILITY_ID_PATTERN}
     */
    Set<String> requiredCapabilities();

    /**
     * Returns the class of the entitlement this artifact requires. There is no default: an
     * implementation that does not state its class is not silently given the weaker one.
     *
     * @return the class, never {@code null}
     */
    EntitlementClass entitlementClass();
}
