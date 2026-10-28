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
 * <p>Three classes are production classes ({@link #isProductionClass()}): they serve live traffic.
 * The other three do not. The distinction describes the environment only; an artifact that requires an
 * entitlement needs a verified manifest in every one of them.
 *
 * <p>The kernel reads the declaration from the configuration key {@value #CONFIG_KEY}. A kernel
 * that declares nothing runs as {@link #DEVELOPMENT}.
 *
 * @since 0.13
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.ShortMethodName"})
public enum ExecutionEnvironment {

    /** Evaluation, local development and test. */
    DEVELOPMENT("development", false),
    /** Pre-release validation. */
    STAGING("staging", false),
    /** Live traffic. */
    PRODUCTION("production", true),
    /** Load simulation on live data planes. */
    PRODUCTION_LOAD_SIM("production-load-sim", true),
    /** Dormant disaster-recovery standby that serves no traffic. */
    DR_COLD("dr-cold", false),
    /** Hot or warm disaster-recovery standby that serves traffic. */
    DR_HOT("dr-hot", true);

    /** Configuration key that declares the environment. */
    public static final String CONFIG_KEY = "environment";

    private final String id;
    private final boolean productionClass;

    ExecutionEnvironment(String id, boolean productionClass) {
        this.id = id;
        this.productionClass = productionClass;
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
     * Reports whether this environment serves live traffic.
     *
     * @return {@code true} for {@link #PRODUCTION}, {@link #PRODUCTION_LOAD_SIM} and {@link #DR_HOT}
     */
    public boolean isProductionClass() {
        return productionClass;
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
