/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.util.Objects;

/**
 * A declaration site discovered by bytecode scanning that touches reflection, dynamic resources,
 * or foreign function and memory APIs.
 *
 * @param declaringClass binary name of the class declaring the site
 * @param methodName     name of the method containing the site, or {@code "<constant-pool>"} for constant-pool references
 * @param api            canonical name or representation of the flagged API
 * @since 0.13
 */
public record DeclarationSite(
        String declaringClass,
        String methodName,
        String api
) implements Comparable<DeclarationSite> {

    /**
     * Constructs a declaration site with non-null components.
     *
     * @param declaringClass binary name of the class declaring the site
     * @param methodName     name of the method containing the site, or {@code "<constant-pool>"} for constant-pool references
     * @param api            canonical name of the flagged API
     */
    public DeclarationSite {
        Objects.requireNonNull(declaringClass, "declaringClass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(api, "api");
    }

    /**
     * Formats this declaration site in the canonical {@code X.m → API} format used in failure reports.
     *
     * @return the formatted representation
     */
    @Override
    public String toString() {
        return declaringClass + "." + methodName + " → " + api;
    }

    @Override
    public int compareTo(DeclarationSite other) {
        int cmp = declaringClass.compareTo(other.declaringClass);
        if (cmp != 0) {
            return cmp;
        }
        cmp = methodName.compareTo(other.methodName);
        if (cmp != 0) {
            return cmp;
        }
        return api.compareTo(other.api);
    }
}
