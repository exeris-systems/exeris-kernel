/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.bootstrap;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, identity-free selector expressing which subsystems to activate at boot.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Full stack — all subsystems from all SubsystemProviders
 * BootstrapSelector.all()
 *
 * // Selective — only persistence and its transitive dependencies
 * BootstrapSelector.forNames("persistence")
 *
 * // Headless batch worker — no HTTP transport
 * BootstrapSelector.forNames("persistence", "events", "flow")
 * }</pre>
 *
 * <h2>Closure Expansion</h2>
 * <p>The {@code SubsystemOrchestrator} in {@code exeris-kernel-core} expands this
 * selector to its full transitive closure: if {@code "persistence"} is requested and
 * it declares {@code dependsOn("memory", "telemetry")}, then {@code memory} and
 * {@code telemetry} are automatically pulled into the active set — no manual listing.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Declared {@code value record} on the `preview` line (JEP 401, preview in JDK 28). The
 * distributed line compiles the same source as an identity {@code record}; the modifier is the
 * only difference, and it is asserted by {@code Class::isValue} in the module's value-carrier
 * registry test rather than left to a one-time inspection.
 *
 * @param requestedNames explicitly requested subsystem names; empty when {@link #selectAll} is true
 * @param selectAll      {@code true} = activate every subsystem found via ServiceLoader
 * @since 0.5.0
 */
public value record BootstrapSelector(Set<String> requestedNames, boolean selectAll) {

    // -------------------------------------------------------------------------
    // Compact canonical constructor — validates invariants
    // -------------------------------------------------------------------------

    public BootstrapSelector {
        // Defensive copy to prevent external mutation; Set.copyOf returns immutable set
        requestedNames = (requestedNames == null) ? Set.of() : Set.copyOf(requestedNames);
        // If selectAll=true, requestedNames is ignored — no invariant to enforce
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a selector that activates <em>all</em> subsystems discovered via
     * {@code ServiceLoader<SubsystemProvider>}.
     *
     * @return the "all" selector; always the same logical value
     */
    public static BootstrapSelector all() {
        return new BootstrapSelector(Set.of(), true);
    }

    /**
     * Creates a selector that activates <em>zero</em> subsystems.
     *
     * <p>Useful in tests and minimal bootstrap scenarios where the kernel scope
     * ({@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG}) is
     * needed but no subsystem lifecycle is required.
     *
     * @return the "none" selector
     * @since 0.5.0
     */
    public static BootstrapSelector none() {
        return new BootstrapSelector(Set.of(), false);
    }

    /**
     * Creates a selector for the named subsystems plus their transitive dependencies.
     *
     * <p>Example: {@code BootstrapSelector.forNames("persistence")} automatically
     * pulls in {@code memory} and {@code telemetry} via dependency closure.
     *
     * <p>Each name is normalized: leading/trailing whitespace is stripped and the
     * string is lowercased to match the {@link eu.exeris.kernel.spi.bootstrap.Subsystem#name()}
     * contract (lowercase, hyphen-separated). Passing {@code " Persistence "} is
     * therefore equivalent to passing {@code "persistence"}.
     *
     * @param names one or more subsystem names (e.g., {@code "persistence"});
     *              each entry must be non-null and non-blank after trimming
     * @return selective selector
     * @throws IllegalArgumentException if {@code names} is empty, or if any individual
     *                                  name is {@code null} or blank after trimming
     */
    public static BootstrapSelector forNames(String... names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException(
                    "BootstrapSelector.forNames() requires at least one subsystem name. "
                    + "Use BootstrapSelector.all() to activate everything.");
        }
        String[] normalized = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name == null) {
                throw new IllegalArgumentException(
                        "BootstrapSelector.forNames(): null subsystem name at index " + i + ".");
            }
            String trimmed = name.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        "BootstrapSelector.forNames(): blank subsystem name at index " + i
                        + ". Subsystem names must be non-empty strings.");
            }
            normalized[i] = trimmed;
        }
        return new BootstrapSelector(Set.copyOf(Arrays.asList(normalized)), false);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code name} is explicitly requested
     * (irrelevant when {@link #selectAll()} is {@code true}).
     *
     * @param name subsystem name to check
     * @return {@code true} if explicitly included
     */
    public boolean includes(String name) {
        return selectAll || requestedNames.contains(name);
    }

    /**
     * Returns {@code true} if this selector activates all discovered subsystems.
     *
     * @return {@code true} for {@link #all()} selector
     */
    public boolean isAll() {
        return selectAll;
    }

    @Override
    public String toString() {
        return selectAll ? "ALL" : requestedNames.toString();
    }
}



