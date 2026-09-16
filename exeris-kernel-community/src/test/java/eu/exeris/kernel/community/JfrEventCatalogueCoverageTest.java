/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.core.telemetry.jfr.CoreJfrEventCatalogue;
import jdk.jfr.Event;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one property that keeps the warm-up catalogues honest: every JFR event class in the
 * kernel is in exactly one bucket — warmed when its subsystem starts, or deliberately left cold.
 *
 * <h2>Why a guard and not a review habit</h2>
 * <p>A catalogue of class <em>names</em> cannot be checked by the compiler: two thirds of this
 * kernel's event classes are package-private, so no single class can name them with a literal, and a
 * name that no longer resolves fails silently at runtime — {@code JfrEventWarmup} logs it and carries
 * on, which is the right behaviour there and useless as enforcement. Nothing else would notice a new
 * event class that nobody classified, and the failure it causes is the one this whole line of work
 * started from: a cold class initialising on a virtual thread, pinning a carrier, and surfacing as a
 * carrier-pinning regression somewhere else entirely.
 *
 * <p>So the check runs both ways. Every name in a catalogue must resolve to a real
 * {@link Event} subclass in the module it claims, and every {@link Event} subclass the module
 * actually declares must appear in one of the two buckets.
 *
 * <p>This module is the first point in the reactor where Core and Community are both on one
 * classpath, which is why the guard lives here — the same reasoning, and the same placement, as
 * {@code KernelTierDirectionArchitectureTest}.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel")
class JfrEventCatalogueCoverageTest {

    @ArchTest
    static void coreEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.core.",
                CoreJfrEventCatalogue.allHotPath(), CoreJfrEventCatalogue.deliberatelyCold());
    }

    @ArchTest
    static void communityEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.community.",
                CommunityJfrEventCatalogue.allHotPath(), CommunityJfrEventCatalogue.deliberatelyCold());
    }

    @ArchTest
    static void everySubsystemNameTheCatalogueCoversIsOneASubsystemReports(JavaClasses classes) {
        // The dispatch is on Subsystem.name(). A group keyed by a name no subsystem reports would
        // never be warmed and nothing else would say so — the entries would simply sit there.
        Set<String> subsystemNames = classes.stream()
                .filter(c -> c.getPackageName().equals("eu.exeris.kernel.community.bootstrap"))
                .map(JavaClass::getSimpleName)
                .filter(n -> n.startsWith("Community") && n.endsWith("Subsystem"))
                .map(n -> n.substring("Community".length(), n.length() - "Subsystem".length())
                        .toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(subsystemNames)
                .withFailMessage("no Community subsystem classes were found; the check below would pass vacuously")
                .isNotEmpty();

        for (String name : subsystemNames) {
            boolean covered = !CoreJfrEventCatalogue.hotPathFor(name).isEmpty()
                    || !CommunityJfrEventCatalogue.hotPathFor(name).isEmpty();
            assertThat(covered)
                    .withFailMessage("subsystem '%s' warms nothing in either catalogue — if that is deliberate "
                            + "its events belong in deliberatelyCold(), and if it is not, the group is missing",
                            name)
                    .isTrue();
        }
    }

    private static void assertClassified(JavaClasses classes, String modulePrefix,
                                         List<String> hotPath, List<String> cold) {
        Set<String> declared = classes.stream()
                .filter(c -> c.getName().startsWith(modulePrefix))
                .filter(c -> c.isAssignableTo(Event.class))
                .filter(c -> !c.getName().equals(Event.class.getName()))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declared)
                .withFailMessage("no JFR event classes were found under %s — the classpath changed and every "
                        + "assertion below would pass on the empty set", modulePrefix)
                .isNotEmpty();

        Set<String> classified = new TreeSet<>(hotPath);
        classified.addAll(cold);

        assertThat(classified)
                .withFailMessage("a class is in both catalogue buckets at once: %s",
                        intersection(hotPath, cold))
                .hasSize(hotPath.size() + cold.size());

        assertThat(difference(declared, classified))
                .withFailMessage("JFR event class(es) under %s are in neither catalogue bucket. Add each to its "
                        + "subsystem's hot-path group, or to deliberatelyCold() with the reason it can wait: %s",
                        modulePrefix, difference(declared, classified))
                .isEmpty();

        assertThat(difference(classified, declared))
                .withFailMessage("catalogue name(s) under %s do not resolve to a JFR event class in this module — "
                        + "renamed, moved, or deleted: %s", modulePrefix, difference(classified, declared))
                .isEmpty();
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }

    private static Set<String> intersection(List<String> left, List<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.retainAll(right);
        return out;
    }
}
