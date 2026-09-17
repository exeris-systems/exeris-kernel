/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import jdk.jfr.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards this driver's warm-up catalogue the way {@code JfrEventCatalogueCoverageTest} guards the
 * engine's: every JFR event class this module declares is in exactly one bucket, and every name in a
 * bucket resolves to one.
 *
 * <p>It has to live here. The Community guard runs in the module this one depends on, so it cannot
 * see these classes at all — a driver that ships its own events ships its own guard, or it ships an
 * unchecked list.
 *
 * <p>The event classes are enumerated by walking this module's compiled output rather than through
 * ArchUnit, which this module does not depend on. Walking {@code target/classes} is enough for one
 * module's own output and adds no dependency to a driver artifact.
 */
@DisplayName("KafkaJfrEventCatalogue: every event class in this module is classified")
class KafkaJfrEventCatalogueTest {

    private static final Path CLASSES = Path.of("target", "classes");

    @Test
    @DisplayName("declared event classes and catalogue entries are the same set")
    void everyEventClassIsClassified() throws IOException {
        Set<String> declared = declaredEventClasses();

        assertThat(declared)
                .withFailMessage("no JFR event classes were found under %s — the module layout changed and this "
                        + "check would pass on the empty set", CLASSES.toAbsolutePath())
                .isNotEmpty();

        Set<String> classified = new TreeSet<>(KafkaJfrEventCatalogue.hotPath());
        classified.addAll(KafkaJfrEventCatalogue.deliberatelyCold());

        assertThat(difference(declared, classified))
                .withFailMessage("JFR event class(es) in this module are in neither catalogue bucket — add each to "
                        + "hotPath(), or to deliberatelyCold() with the reason it can wait: %s",
                        difference(declared, classified))
                .isEmpty();

        assertThat(difference(classified, declared))
                .withFailMessage("catalogue name(s) do not resolve to a JFR event class in this module — renamed, "
                        + "moved, or deleted: %s", difference(classified, declared))
                .isEmpty();
    }

    @Test
    @DisplayName("warming this driver's events initialises them and does not throw")
    void warmingIsSurvivable() throws ClassNotFoundException {
        KafkaJfrEventCatalogue.warmHotPath();

        for (String name : KafkaJfrEventCatalogue.hotPath()) {
            // Loaded with initialize=false: if the warm-up did its work this only confirms the name
            // resolves, and the assertion below is what says it is an event class at all.
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            assertThat(Event.class.isAssignableFrom(type))
                    .withFailMessage("%s is in the hot-path list but is not a jdk.jfr.Event", name)
                    .isTrue();
        }
    }

    private static Set<String> declaredEventClasses() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(CLASSES)) {
            for (Path classFile : tree.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = CLASSES.relativize(classFile).toString()
                        .replace(".class", "")
                        .replace('/', '.');
                try {
                    Class<?> type = Class.forName(name, false, KafkaJfrEventCatalogueTest.class.getClassLoader());
                    if (Event.class.isAssignableFrom(type) && !Event.class.equals(type)) {
                        found.add(name);
                    }
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // A class this module compiles but cannot link in a test JVM is not an event
                    // class anyone can emit either; nothing to classify.
                }
            }
        }
        return found;
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }
}
