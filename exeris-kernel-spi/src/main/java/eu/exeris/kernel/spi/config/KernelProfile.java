/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.config;

/**
 * The three deployment postures a kernel can boot under, each a fixed answer to the same
 * three questions.
 *
 * <p>The profile controls:
 * <ul>
 *   <li>Whether graceful degradation of optional subsystems is permitted.</li>
 *   <li>Whether in-memory database backends are acceptable.</li>
 *   <li>Whether full exception details are surfaced to callers.</li>
 * </ul>
 *
 * <p>The profile is resolved once at L0 bootstrap by the active {@link ConfigProvider}
 * and propagated immutably via {@link ConfigProvider.KernelSettings#profile()}.
 * {@link ConfigProvider} implementations are the <em>single source of truth</em> —
 * {@link #loadFromEnvironment()} is a resolution helper for those implementations,
 * not an independent authority.
 *
 * <h2>Resolution Order (inside ConfigProvider implementations)</h2>
 * <ol>
 *   <li>{@code EXERIS_KERNEL_PROFILE} environment variable.</li>
 *   <li>{@code exeris.kernel.profile} system property.</li>
 *   <li>Default: {@link #PROD} (safest fallback).</li>
 * </ol>
 *
 * @apiNote Read the profile through {@link ConfigProvider.KernelSettings#profile()}, or
 *          through {@link #fromName(String)} inside a {@code ConfigProvider} implementation;
 *          never by calling {@link #loadFromEnvironment()} directly from application or
 *          subsystem code, which would answer the question from the environment rather than
 *          from the provider that already decided it.
 * @since 0.5
 */
public enum KernelProfile {

    /** Development — degradation allowed, in-memory DB allowed, full error disclosure. */
    DEV("dev",   true,  true,  true),

    /** Test / CI — degradation allowed, in-memory DB allowed, no full disclosure. */
    TEST("test", true,  true,  false),

    /** Production — no degradation, no in-memory DB, no full disclosure. */
    PROD("prod", false, false, false);

    // -------------------------------------------------------------------------

    private final String   name;
    private final boolean  allowsDegradation;
    private final boolean  allowsInMemoryDb;
    private final boolean  enableFullErrorDisclosure;

    KernelProfile(String name,
                  boolean allowsDegradation,
                  boolean allowsInMemoryDb,
                  boolean enableFullErrorDisclosure) {
        this.name                     = name;
        this.allowsDegradation        = allowsDegradation;
        this.allowsInMemoryDb         = allowsInMemoryDb;
        this.enableFullErrorDisclosure = enableFullErrorDisclosure;
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Reports whether the kernel still reaches a running state when an optional subsystem
     * fails to initialise, instead of aborting the boot on the first failure.
     *
     * @return whether optional subsystems may fail without aborting bootstrap
     */
    public boolean allowsDegradation() {
        return allowsDegradation;
    }

    /**
     * Reports whether a persistence driver may satisfy the kernel with an in-memory backend
     * rather than a real database.
     *
     * @return whether in-memory database backends are acceptable
     */
    public boolean allowsInMemoryDb() {
        return allowsInMemoryDb;
    }

    /**
     * Reports whether an exception may reach a caller carrying full internal detail, rather
     * than being reduced to an error code and a message that discloses nothing about the
     * internals.
     *
     * @return whether full exception detail is surfaced to callers
     */
    public boolean enablesFullErrorDisclosure() {
        return enableFullErrorDisclosure;
    }

    /**
     * Reports whether this is the development profile — the one that permits degradation,
     * in-memory backends and full error disclosure all three.
     *
     * @return {@code true} for {@link #DEV}
     */
    public boolean isDev() {
        return this == DEV;
    }

    /**
     * Reports whether this is the test profile, which permits degradation and in-memory
     * backends but still withholds full error disclosure.
     *
     * @return {@code true} for {@link #TEST}
     */
    public boolean isTest() {
        return this == TEST;
    }

    /**
     * Reports whether this is the production profile — the one nothing has to configure,
     * since it is what an unset or blank profile resolves to, and the one that permits
     * neither degradation nor an in-memory backend nor error disclosure.
     *
     * @return {@code true} for {@link #PROD}
     */
    public boolean isProd() {
        return this == PROD;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Resolves the profile from a string name, case-insensitively and ignoring surrounding
     * whitespace.
     *
     * @param name profile name ({@code "dev"}, {@code "test"}, {@code "prod"});
     *             {@code null} or blank → {@link #PROD}
     * @return matching profile
     * @throws IllegalArgumentException if the name is non-blank and names no profile — an
     *                                  unrecognised profile is refused rather than quietly
     *                                  treated as {@link #PROD}
     */
    public static KernelProfile fromName(String name) {
        if (name == null || name.isBlank()) {
            return PROD;
        }
        for (KernelProfile p : values()) {
            if (p.name.equalsIgnoreCase(name.strip())) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown kernel profile: '" + name
                + "'. Valid values: dev, test, prod.");
    }

    /**
     * Resolves the profile from the process environment, for a {@link ConfigProvider}
     * implementation that has no explicitly configured profile to hand.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code EXERIS_KERNEL_PROFILE} env var.</li>
     *   <li>{@code exeris.kernel.profile} system property.</li>
     *   <li>Fallback: {@link #PROD}.</li>
     * </ol>
     *
     * @return active kernel profile; never {@code null}
     * @throws IllegalArgumentException if either source holds a non-blank value that names no
     *                                  profile — an unreadable profile fails the boot rather
     *                                  than silently resolving to {@link #PROD}
     * @apiNote Call this from a {@code ConfigProvider} implementation during L0 bootstrap and
     *          nowhere else. Application and subsystem code reads the profile through
     *          {@link ConfigProvider.KernelSettings#profile()} on the provider bound to
     *          {@code KernelProviders.CURRENT_CONFIG}; reading the environment again would
     *          answer a second, unsynchronised question, and a provider that resolved the
     *          profile from a source of its own would be contradicted by it.
     */
    public static KernelProfile loadFromEnvironment() {
        String env = System.getenv("EXERIS_KERNEL_PROFILE");
        if (env != null && !env.isBlank()) {
            return fromName(env);
        }

        String prop = System.getProperty("exeris.kernel.profile");
        if (prop != null && !prop.isBlank()) {
            return fromName(prop);
        }

        return PROD;
    }

    /**
     * Returns the canonical lower-case spelling of this profile — {@code dev}, {@code test} or
     * {@code prod} — which is both what {@link #fromName(String)} accepts and what the
     * bootstrap telemetry carries, rather than the enum constant name.
     *
     * @return the canonical profile name; never {@code null}
     */
    @Override
    public String toString() {
        return name;
    }
}



