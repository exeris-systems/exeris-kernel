/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import java.util.List;
import java.util.Map;

/**
 * One module's JFR event classes, split into the ones a subsystem warms when it starts and the ones
 * it deliberately leaves to initialise at their first emit.
 *
 * <p>The behaviour lives here and the data lives in the per-module catalogues, because the split is
 * the same question in every module and only the answers differ. {@link JfrEventWarmup} states why
 * warming matters at all; a module's own catalogue states why each class is on the side it is on.
 *
 * <p>Holds fully-qualified names rather than class literals: two thirds of this kernel's event
 * classes are package-private, so no single class could name them otherwise, and nothing here loads
 * a class until {@link #warmHotPath(String)} asks for one. A name that no longer resolves is
 * invisible to this type — the guard test that matches both buckets against the event classes a
 * module actually declares is what catches it.
 *
 * @since 0.12
 * @see JfrEventWarmup
 */
public final class JfrEventCatalogue {

    private final Map<String, List<String>> hotPath;
    private final List<String> deliberatelyCold;

    /**
     * Creates a catalogue over one module's event classes.
     *
     * @param hotPath          hot-path classes keyed by the {@code Subsystem.name()} that warms
     *                         them; must not be {@code null}
     * @param deliberatelyCold classes this module does not warm; must not be {@code null}
     */
    public JfrEventCatalogue(Map<String, List<String>> hotPath, List<String> deliberatelyCold) {
        this.hotPath = Map.copyOf(hotPath);
        this.deliberatelyCold = List.copyOf(deliberatelyCold);
    }

    /**
     * Warms the hot-path event classes of one subsystem, on the calling thread.
     *
     * <p>Called once per subsystem start, before the subsystem serves anything. A name this
     * catalogue does not cover is not an error: a kernel is free to run subsystems a module says
     * nothing about, and they simply have nothing to warm here.
     *
     * @param subsystemName the starting subsystem's {@code Subsystem.name()}; may be {@code null}
     */
    public void warmHotPath(String subsystemName) {
        JfrEventWarmup.ensureInitialised(hotPathFor(subsystemName));
    }

    /**
     * The hot-path event classes of one subsystem.
     *
     * @param subsystemName the subsystem's {@code Subsystem.name()}; may be {@code null}
     * @return their fully-qualified names, empty for a subsystem this catalogue does not cover
     */
    public List<String> hotPathFor(String subsystemName) {
        if (subsystemName == null) {
            return List.of();
        }
        return hotPath.getOrDefault(subsystemName, List.of());
    }

    /**
     * Every hot-path event class in this catalogue, across all subsystems.
     *
     * @return their fully-qualified names; never {@code null}
     */
    public List<String> allHotPath() {
        return hotPath.values().stream().flatMap(List::stream).toList();
    }

    /**
     * The subsystem names this catalogue warms something for.
     *
     * @return the hot-path keys, sorted; never {@code null}
     */
    public List<String> warmedSubsystems() {
        return hotPath.keySet().stream().sorted().toList();
    }

    /**
     * Event classes this module deliberately does not warm.
     *
     * <p>Not a leftover list: it is the other half of what the guard test enforces, so that a new
     * event class has to be placed in one bucket or the other and choosing this one is a decision
     * rather than an omission.
     *
     * @return their fully-qualified names; never {@code null}
     */
    public List<String> deliberatelyCold() {
        return deliberatelyCold;
    }
}
