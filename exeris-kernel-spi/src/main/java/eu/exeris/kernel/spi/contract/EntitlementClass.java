/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import java.util.Optional;

/**
 * The licence class of a capability artifact: the kind of entitlement running it requires.
 *
 * <p>A class is a technical property its build carries, not contract vocabulary. An
 * {@link EntitlementRequirement} states the class of the capabilities it declares, and an issuer key
 * is allowed to entitle only some classes, so a key that may issue commercial licences cannot be used
 * to entitle an enterprise-private artifact.
 *
 * @since 0.13
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.ShortMethodName"})
public enum EntitlementClass {

    /** An artifact licensed commercially. */
    COMMERCIAL("commercial"),
    /** An artifact licensed only under an enterprise agreement. */
    ENTERPRISE_PRIVATE("enterprise-private");

    private final String id;

    EntitlementClass(String id) {
        this.id = id;
    }

    /**
     * Returns the identifier a licence registry and an issuer use for this class.
     *
     * @return lowercase kebab-case identifier, e.g. {@code "enterprise-private"}
     */
    public String id() {
        return id;
    }

    /**
     * Resolves an identifier to its class. The match is exact: unlike
     * {@link ExecutionEnvironment#fromId(String)}, case and surrounding whitespace are not folded, so
     * an identifier that is not written as the registry writes it names no class.
     *
     * @param id class identifier
     * @return the matching class, or empty when {@code id} is {@code null} or names none
     */
    public static Optional<EntitlementClass> fromId(String id) {
        for (EntitlementClass entitlementClass : values()) {
            if (entitlementClass.id.equals(id)) {
                return Optional.of(entitlementClass);
            }
        }
        return Optional.empty();
    }
}
