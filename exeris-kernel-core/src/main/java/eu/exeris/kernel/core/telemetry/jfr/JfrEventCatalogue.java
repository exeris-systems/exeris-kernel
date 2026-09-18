/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final ClassLoader loader;

    /**
     * Creates a catalogue over one module's event classes.
     *
     * @param owner            a class from the module that declares these events; its class loader
     *                         is the one the names are resolved through, because a module's events
     *                         ship in its own artifact and not in this one. Must not be
     *                         {@code null}
     * @param hotPath          hot-path classes keyed by the {@code Subsystem.name()} that warms
     *                         them; must not be {@code null}
     * @param deliberatelyCold classes this module does not warm; must not be {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if a class name is blank, or if one name appears in both
     *                                  buckets
     */
    @SuppressWarnings("PMD.UseProperClassLoader") // the owner's loader, deliberately — see below
    public JfrEventCatalogue(Class<?> owner, Map<String, List<String>> hotPath, List<String> deliberatelyCold) {
        // Checked rather than left to NPE somewhere inside the copies below. A catalogue is built in
        // its holder's <clinit>, so a bad argument surfaces as an ExceptionInInitializerError at
        // boot; without these the message names neither the catalogue nor the entry.
        //
        // The Supplier overload, not the String one: this runs in a <clinit> on the boot path, and
        // the String form builds every message by concatenation before finding out that none of
        // them is needed. Which is every run but the broken one.
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hotPath, () -> "hotPath of the catalogue owned by " + owner.getName());
        Objects.requireNonNull(deliberatelyCold,
                () -> "deliberatelyCold of the catalogue owned by " + owner.getName());
        // Deep, because Map.copyOf alone would share the value lists — a caller holding the list
        // it passed could still empty a group after the catalogue was built. The staging map is a
        // LinkedHashMap so the copy itself is deterministic; what comes out of Map.copyOf below is
        // NOT ordered, and nothing here promises it is. An earlier version of this constructor kept
        // the LinkedHashMap to preserve declaration order, which was doubly wrong: no reader wants
        // that order, and both production catalogues hand this constructor a Map.ofEntries(...)
        // whose order is already salted before the copy starts.
        Map<String, List<String>> copied = new LinkedHashMap<>();
        hotPath.forEach((subsystem, classes) -> {
            Objects.requireNonNull(subsystem, () -> "subsystem name in the catalogue owned by " + owner.getName());
            Objects.requireNonNull(classes, () -> "hot-path group '" + subsystem + "'");
            // The group label is passed, not the finished sentence: built here it was concatenated
            // once per class name — 78 times on the Core catalogue — to be discarded every time.
            classes.forEach(name -> requireName(name, subsystem, owner));
            copied.put(subsystem, List.copyOf(classes));
        });
        deliberatelyCold.forEach(name -> requireName(name, null, owner));
        this.hotPath = Map.copyOf(copied);
        this.deliberatelyCold = List.copyOf(deliberatelyCold);
        // A name in both buckets is a contradiction the coverage guard cannot see: it matches the
        // union of the two against the module's declared event classes, and a name counted twice
        // still lands in that union.
        this.hotPath.values().stream()
                .flatMap(List::stream)
                .filter(this.deliberatelyCold::contains)
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("'" + name + "' is both warmed and deliberately cold in "
                            + "the catalogue owned by " + owner.getName() + " — it can only be one");
                });
        // The owning module's loader, and not the thread context loader PMD suggests: these classes
        // ship in that module's artifact, while the context loader belongs to whatever called in —
        // an application's, a build tool's, or on a virtual thread whatever it inherited. Resolving
        // kernel classes through it would make a warm-up succeed or fail by container convention.
        // Null is a legitimate answer here — it means the bootstrap loader, which is what
        // Class.forName(name, true, null) then resolves against — so it is not rejected.
        this.loader = owner.getClassLoader();
    }

    /**
     * Rejects a class name that could never resolve, naming where it was found.
     *
     * @param name  the catalogue entry
     * @param group the hot-path group it sits in, or {@code null} for the cold list; used only to
     *              build the message, and only when one is needed
     * @param owner the catalogue's owner, for the message
     */
    private static void requireName(String name, String group, Class<?> owner) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("blank JFR event class name in "
                    + (group == null ? "the cold list" : "hot-path group '" + group + "'")
                    + " of the catalogue owned by " + owner.getName());
        }
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
        JfrEventWarmup.ensureInitialised(hotPathFor(subsystemName), loader);
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
     * <p><strong>No defined order.</strong> Entries within one group keep the order that group was
     * written in; the groups themselves come out in whatever order the backing immutable map
     * iterates, which is salted per JVM run. Nothing needs more than that — the one caller collects
     * this into a {@code TreeSet}, and {@link #warmHotPath} reads a single group rather than this.
     * A caller that wants a stable sequence sorts it.
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
