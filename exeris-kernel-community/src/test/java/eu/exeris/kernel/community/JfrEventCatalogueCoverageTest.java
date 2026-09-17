/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.core.telemetry.jfr.CoreJfrEventCatalogue;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
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
 * {@code KernelTierDirectionArchitectureTest}. Test classes are excluded: a fixture subsystem in a
 * test has no hot path to warm, and judging one would only teach the next author to work around
 * this file.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel", importOptions = ImportOption.DoNotIncludeTests.class)
class JfrEventCatalogueCoverageTest {

    @ArchTest
    static void coreEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.core.",
                CoreJfrEventCatalogue.catalogue().allHotPath(),
                CoreJfrEventCatalogue.catalogue().deliberatelyCold());
    }

    @ArchTest
    static void communityEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.community.",
                CommunityJfrEventCatalogue.catalogue().allHotPath(),
                CommunityJfrEventCatalogue.catalogue().deliberatelyCold());
    }

    @ArchTest
    static void everyCatalogueKeyIsASubsystemThatCanWarmIt(JavaClasses classes) {
        // The direction that matters. A group keyed by a name no subsystem reports is never warmed,
        // and nothing else would say so — its entries would simply sit there looking classified.
        // This check earns its place: it is what a `telemetry` group, keyed on a subsystem this
        // kernel does not have, was caught by.
        Set<String> subsystemNames = communitySubsystemNames(classes);

        assertThat(subsystemNames)
                .withFailMessage("no Community subsystem classes were found; every check below would pass vacuously")
                .isNotEmpty();

        Set<String> keys = new TreeSet<>(CoreJfrEventCatalogue.catalogue().warmedSubsystems());
        keys.addAll(CommunityJfrEventCatalogue.catalogue().warmedSubsystems());

        assertThat(difference(keys, subsystemNames))
                .withFailMessage("catalogue group(s) keyed on a name no Subsystem reports, so nothing warms them: "
                        + "%s — either the key is wrong, or those events belong in deliberatelyCold()",
                        difference(keys, subsystemNames))
                .isEmpty();
    }

    @ArchTest
    static void everySubsystemWarmsItsOwnCatalogueGroup(JavaClasses classes) {
        // The other direction, and the one a single shared hook got wrong: three subsystems
        // implemented Subsystem directly and never reached the base class the warm-up hung off, so
        // their groups — memory's allocation events among them — were never warmed by anything.
        // The call now sits in each start(); this is what keeps a thirteenth subsystem from
        // forgetting it.
        List<JavaClass> subsystems = concreteSubsystems(classes);

        assertThat(subsystems)
                .withFailMessage("no concrete Community Subsystem classes were found; this check would pass vacuously")
                .isNotEmpty();

        for (JavaClass subsystem : subsystems) {
            assertThat(warmsItsGroup(subsystem))
                    .withFailMessage("%s never calls CommunityJfrEventCatalogue.warmHotPath — directly or in a "
                            + "superclass — so its hot-path event classes initialise on whichever virtual thread "
                            + "emits one first", subsystem.getSimpleName())
                    .isTrue();
        }
    }

    /** Whether this class, or one it inherits from, calls the driver catalogue's warm-up. */
    private static boolean warmsItsGroup(JavaClass subsystem) {
        for (JavaClass c = subsystem; c != null; c = c.getRawSuperclass().orElse(null)) {
            boolean calls = c.getMethodCallsFromSelf().stream()
                    .anyMatch(call -> "warmHotPath".equals(call.getTarget().getName())
                            && call.getTargetOwner().getName()
                                    .equals(CommunityJfrEventCatalogue.class.getName()));
            if (calls) {
                return true;
            }
        }
        return false;
    }

    /**
     * The names the subsystems actually report, read from {@code Subsystem.name()}.
     *
     * <p>Derived by instantiating each one and calling the method, not by lowercasing a class name:
     * the catalogue is keyed on what {@code name()} returns, and a check that reconstructs the key
     * from the class name instead never reads the property it claims to check. {@code name()} is a
     * constant on every implementation and needs no kernel context to call.
     */
    private static Set<String> communitySubsystemNames(JavaClasses classes) {
        Set<String> names = new TreeSet<>();
        for (JavaClass javaClass : concreteSubsystems(classes)) {
            try {
                Class<?> type = Class.forName(javaClass.getName());
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                names.add(((Subsystem) constructor.newInstance()).name());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read Subsystem.name() from " + javaClass.getName()
                        + " — the guard cannot check the catalogue's keys without it", e);
            }
        }
        return names;
    }

    private static List<JavaClass> concreteSubsystems(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getPackageName().equals("eu.exeris.kernel.community.bootstrap"))
                .filter(c -> c.isAssignableTo(Subsystem.class))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .toList();
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

        assertThat(intersection(hotPath, cold))
                .withFailMessage("class(es) in both catalogue buckets at once: %s", intersection(hotPath, cold))
                .isEmpty();

        assertThat(duplicatesWithin(hotPath))
                .withFailMessage("name(s) listed twice in the hot-path map: %s", duplicatesWithin(hotPath))
                .isEmpty();

        assertThat(duplicatesWithin(cold))
                .withFailMessage("name(s) listed twice in deliberatelyCold(): %s", duplicatesWithin(cold))
                .isEmpty();

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

    private static Set<String> duplicatesWithin(List<String> names) {
        Set<String> seen = new TreeSet<>();
        Set<String> twice = new TreeSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                twice.add(name);
            }
        }
        return twice;
    }

    private static Set<String> intersection(List<String> left, List<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.retainAll(right);
        return out;
    }
}
