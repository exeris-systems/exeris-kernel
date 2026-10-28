/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What an issuer key is allowed to entitle. A manifest is valid only inside the scope of the key that
 * signed it, whatever the manifest itself says; the scope is fixed with the key in the trust store.
 *
 * <p>A key is one of three kinds, each with a constant: {@link #PROD} for production licences,
 * {@link #DEV} for developer licences and {@link #ENT} for enterprise licences. A key with a
 * {@linkplain #workloadCeiling() workload ceiling} is a developer key ({@link #isDeveloperScope()}).
 *
 * @param environments     the environments a manifest signed by the key may authorize; never empty
 * @param maxValidity      the longest a manifest may stay bootable, measured from {@code issuer.issuedAt} to
 *                         {@code validUntil} plus the grace period; {@code null} for no maximum
 * @param workloadCeiling  the largest workload envelope a manifest may grant, and the requirement that it
 *                         grants one; {@code null} for no ceiling
 * @param classes          the {@link EntitlementClass entitlement classes} the key may entitle; never empty
 * @since 0.13
 */
public value record IssuerKeyScope(
        Set<ExecutionEnvironment> environments,
        Duration maxValidity,
        WorkloadEnvelope workloadCeiling,
        Set<EntitlementClass> classes) {

    /** Production licences: every environment, no maximum validity, no ceiling, commercial artifacts. */
    public static final IssuerKeyScope PROD = new IssuerKeyScope(
            EnumSet.allOf(ExecutionEnvironment.class), null, null, EnumSet.of(EntitlementClass.COMMERCIAL));

    /**
     * Developer licences: development only, at most 30 days, at most 50 requests per second and 10
     * connections with no growth allowance, commercial artifacts.
     */
    public static final IssuerKeyScope DEV = new IssuerKeyScope(
            EnumSet.of(ExecutionEnvironment.DEVELOPMENT),
            Duration.ofDays(30),
            new WorkloadEnvelope(50, 10, 0),
            EnumSet.of(EntitlementClass.COMMERCIAL));

    /** Enterprise licences: every environment, no maximum validity, no ceiling, every entitlement class. */
    public static final IssuerKeyScope ENT = new IssuerKeyScope(
            EnumSet.allOf(ExecutionEnvironment.class), null, null, EnumSet.allOf(EntitlementClass.class));

    /**
     * Compact constructor rejecting an empty set and keeping an immutable copy of each.
     */
    public IssuerKeyScope {
        Objects.requireNonNull(environments, "environments must not be null");
        Objects.requireNonNull(classes, "classes must not be null");
        if (environments.isEmpty()) {
            throw new IllegalArgumentException("environments must not be empty");
        }
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("classes must not be empty");
        }
        if (maxValidity != null && (maxValidity.isNegative() || maxValidity.isZero())) {
            throw new IllegalArgumentException("maxValidity must be positive");
        }
        environments = Set.copyOf(environments);
        classes = Set.copyOf(classes);
    }

    /**
     * Reports whether the key is a developer key.
     *
     * <p>The workload ceiling is the single source of this: a manifest signed by a key with a ceiling runs
     * under the developer-licence workload envelope.
     *
     * @return {@code true} when the scope has a workload ceiling
     */
    public boolean isDeveloperScope() {
        return workloadCeiling != null;
    }
}
