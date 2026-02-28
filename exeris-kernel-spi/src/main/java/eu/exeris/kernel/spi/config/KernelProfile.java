/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.config;

/**
 * Active kernel deployment profile.
 *
 * <p>The profile is resolved once at L0 bootstrap by the active {@link ConfigProvider}
 * and propagated immutably via {@link ConfigProvider.KernelSettings#profile()}.
 * It controls:
 * <ul>
 *   <li>Whether graceful degradation of optional subsystems is permitted.</li>
 *   <li>Whether in-memory database backends are acceptable.</li>
 *   <li>Whether full exception details are surfaced to callers.</li>
 * </ul>
 *
 * <h2>Resolution Order</h2>
 * <ol>
 *   <li>{@code EXERIS_KERNEL_PROFILE} environment variable.</li>
 *   <li>{@code exeris.kernel.profile} system property.</li>
 *   <li>Default: {@link #PROD} (safest fallback).</li>
 * </ol>
 *
 * @since 0.5.0
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

    /** @return whether optional subsystems may fail without aborting bootstrap. */
    public boolean allowsDegradation() {
        return allowsDegradation;
    }

    /** @return whether in-memory database backends are acceptable. */
    public boolean allowsInMemoryDb() {
        return allowsInMemoryDb;
    }

    /** @return whether full exception detail is surfaced to callers. */
    public boolean enablesFullErrorDisclosure() {
        return enableFullErrorDisclosure;
    }

    public boolean isDev() {
        return this == DEV;
    }

    public boolean isTest() {
        return this == TEST;
    }

    public boolean isProd() {
        return this == PROD;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Resolves the profile from a string name (case-insensitive).
     *
     * @param name profile name; {@code null} → {@link #PROD}
     * @return matching profile
     * @throws IllegalArgumentException if the name is non-null and unrecognized
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
     * Resolves the profile from the runtime environment:
     * <ol>
     *   <li>{@code EXERIS_KERNEL_PROFILE} env var.</li>
     *   <li>{@code exeris.kernel.profile} system property.</li>
     *   <li>Fallback: {@link #PROD}.</li>
     * </ol>
     *
     * @return active kernel profile; never {@code null}
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

    @Override
    public String toString() {
        return name;
    }
}



