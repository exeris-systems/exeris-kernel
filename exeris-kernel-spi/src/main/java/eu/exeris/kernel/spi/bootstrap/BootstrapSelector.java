/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.bootstrap;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, Valhalla-ready selector expressing which subsystems to activate at boot.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * // Full stack — all subsystems from all SubsystemProviders
 * BootstrapSelector.all()
 *
 * // Selective — only persistence and its transitive dependencies
 * BootstrapSelector.forNames("persistence")
 *
 * // Headless batch worker — no HTTP transport
 * BootstrapSelector.forNames("persistence", "events", "flow")
 * }
 *
 * <h2>Closure Expansion</h2>
 * <p>The {@code SubsystemOrchestrator} in {@code exeris-kernel-core} expands this
 * selector to its full transitive closure: if {@code "persistence"} is requested and
 * it declares {@code dependsOn("memory", "telemetry")}, then {@code memory} and
 * {@code telemetry} are automatically pulled into the active set — no manual listing.
 * A requested name, or a name the closure reaches, that no {@link SubsystemProvider} on the
 * classpath supplies aborts the boot rather than being silently dropped.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a standard {@code record} with no identity operations. It is
 * designed as a {@code ConfigProvider.ValueCandidate} and may be promoted to
 * a {@code value record} (JEP 401) once the toolchain supports it.
 *
 * <p><b>Allocation:</b> allocates (one immutable {@code Set} copy per construction, plus the
 * normalised names in {@link #forNames(String...)}) — a selector is built once per boot and
 * never on a request path.
 * <p><b>Thread confinement:</b> any thread — the record is immutable and its {@code Set} is
 * a defensive immutable copy, so one selector may be read from any number of threads without
 * synchronisation.
 *
 * @param requestedNames explicitly requested subsystem names; empty when {@link #selectAll} is true
 * @param selectAll      {@code true} = activate every subsystem found via ServiceLoader
 * @since 0.5
 */
public record BootstrapSelector(Set<String> requestedNames, boolean selectAll) {

    // -------------------------------------------------------------------------
    // Compact canonical constructor — validates invariants
    // -------------------------------------------------------------------------

    /**
     * Canonical constructor — normalises the requested-name set so that the record is
     * immutable whatever the caller passes in.
     *
     * <p>A {@code null} set is read as "none requested"; any other set is copied, so the
     * selector never aliases a collection the caller can still mutate. When {@code selectAll}
     * is {@code true} the names are kept but carry no meaning — {@link #includes(String)}
     * answers {@code true} for every name.
     *
     * @param requestedNames explicitly requested subsystem names; may be {@code null}
     * @param selectAll      {@code true} to activate every subsystem discovered via
     *                       {@code ServiceLoader}, ignoring {@code requestedNames}
     * @throws NullPointerException if {@code requestedNames} contains a {@code null} element
     */
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
     * @since 0.5
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
     * Reports whether this selector activates the subsystem called {@code name}.
     *
     * <p>Always {@code true} for the {@link #all()} selector; otherwise membership of
     * {@link #requestedNames()} — the explicitly requested set, <em>before</em> the
     * orchestrator expands it to its dependency closure. A subsystem pulled into the boot
     * only as someone else's dependency is therefore not "included" by this method.
     *
     * @param name subsystem name to check, in the lowercase hyphen-separated form of
     *             {@link Subsystem#name()}
     * @return {@code true} if this selector activates {@code name}
     * @apiNote The match is literal. {@link #forNames(String...)} trims and lowercases what it
     *          is given, but this method does not, so a selector built from
     *          {@code forNames("Persistence")} answers {@code false} to
     *          {@code includes("Persistence")} and {@code true} to {@code includes("persistence")}.
     */
    public boolean includes(String name) {
        return selectAll || requestedNames.contains(name);
    }

    /**
     * Reports whether this selector activates every subsystem discovered through
     * {@code ServiceLoader<SubsystemProvider>} rather than a named subset.
     *
     * @return {@code true} for the {@link #all()} selector; {@code false} for {@link #none()}
     *         and for any selector built by {@link #forNames(String...)}
     */
    public boolean isAll() {
        return selectAll;
    }

    @Override
    public String toString() {
        return selectAll ? "ALL" : requestedNames.toString();
    }
}



