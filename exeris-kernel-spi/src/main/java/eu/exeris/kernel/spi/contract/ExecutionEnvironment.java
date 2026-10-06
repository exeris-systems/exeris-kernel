/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import java.util.Locale;
import java.util.Optional;

/**
 * The environment class a kernel declares it runs in, with the identifiers a license manifest's
 * {@code execution.environments} uses (ADR-088 §1).
 *
 * <p>Three classes are production: they serve live traffic, so code that requires an entitlement
 * may run in them only under a verified manifest. The other three may run anything without one,
 * which is the <i>Build ≠ License</i> boundary of ADR-089.
 *
 * <p>The kernel reads the declaration from the configuration key {@value #CONFIG_KEY}. A kernel
 * that declares nothing runs as {@link #DEVELOPMENT}.
 *
 * @since 0.13
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.ShortMethodName"})
public enum ExecutionEnvironment {

    /** Evaluation, local development and test; no entitlement required. */
    DEVELOPMENT("development", false),
    /** Pre-release validation; no entitlement required. */
    STAGING("staging", false),
    /** Live traffic; entitlement required. */
    PRODUCTION("production", true),
    /** Load simulation on live data planes; entitlement required. */
    PRODUCTION_LOAD_SIM("production-load-sim", true),
    /** Dormant disaster-recovery standby that serves no traffic; no entitlement required. */
    DR_COLD("dr-cold", false),
    /** Hot or warm disaster-recovery standby that serves traffic; entitlement required. */
    DR_HOT("dr-hot", true);

    /** Configuration key that declares the environment. */
    public static final String CONFIG_KEY = "environment";

    private final String id;
    private final boolean requiresEntitlement;

    ExecutionEnvironment(String id, boolean requiresEntitlement) {
        this.id = id;
        this.requiresEntitlement = requiresEntitlement;
    }

    /**
     * Returns the identifier a manifest uses for this environment.
     *
     * @return lowercase kebab-case identifier, e.g. {@code "production-load-sim"}
     */
    public String id() {
        return id;
    }

    /**
     * Reports whether code that requires an entitlement may run here only under a verified manifest.
     *
     * @return {@code true} for the production classes
     */
    public boolean requiresEntitlement() {
        return requiresEntitlement;
    }

    /**
     * Resolves an identifier to its environment, ignoring case and surrounding whitespace.
     *
     * @param id manifest or configuration identifier
     * @return the matching environment, or empty when {@code id} names none
     */
    public static Optional<ExecutionEnvironment> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        for (ExecutionEnvironment environment : values()) {
            if (environment.id.equals(normalized)) {
                return Optional.of(environment);
            }
        }
        return Optional.empty();
    }
}
