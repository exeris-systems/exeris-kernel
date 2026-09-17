/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.tck.contract.JfrEventCatalogueCoverage;
import jdk.jfr.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
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
 * ArchUnit, which this module does not depend on — that adds no dependency to a driver artifact. The
 * output directory is taken from the code source of a class this module declares, not from a
 * {@code target/classes} relative to the working directory: the working directory is a property of
 * whoever launched the JVM, and a guard that silently enumerates nothing is worse than no guard.
 * Binary names are assembled from path elements for the same reason — a separator literal decides
 * this test's verdict on one platform and not another.
 */
@DisplayName("KafkaJfrEventCatalogue: every event class in this module is classified")
class KafkaJfrEventCatalogueTest {

    private static final String CLASS_SUFFIX = ".class";

    private static final Path CLASSES = compiledOutputDirectory();

    @Test
    @DisplayName("declared event classes and catalogue entries are the same set")
    void everyEventClassIsClassified() throws IOException {
        // The enumeration below is this module's own — it has no ArchUnit dependency, so it walks
        // its CodeSource — and the judgement is the shared one, which is the half the Community
        // guard and this test carried in two copies.
        JfrEventCatalogueCoverage.assertBucketsMatchDeclared(
                "this module, under " + CLASSES.toAbsolutePath(),
                declaredEventClasses(),
                KafkaJfrEventCatalogue.catalogue().allHotPath(),
                KafkaJfrEventCatalogue.catalogue().deliberatelyCold());
    }

    @Test
    @DisplayName("warming this driver's events initialises them and does not throw")
    void warmingIsSurvivable() throws ClassNotFoundException {
        KafkaJfrEventCatalogue.warmHotPath();

        for (String name : KafkaJfrEventCatalogue.catalogue().allHotPath()) {
            // Loaded with initialize=false: if the warm-up did its work this only confirms the name
            // resolves, and the assertion below is what says it is an event class at all.
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            assertThat(Event.class.isAssignableFrom(type))
                    .withFailMessage("%s is in the hot-path list but is not a jdk.jfr.Event", name)
                    .isTrue();
        }
    }

    /**
     * This module's compiled output, located through the classpath rather than the working directory.
     *
     * @return the directory holding this module's classes
     */
    private static Path compiledOutputDirectory() {
        CodeSource source = KafkaJfrEventCatalogue.class.getProtectionDomain().getCodeSource();
        assertThat(source)
                .withFailMessage("KafkaJfrEventCatalogue has no code source, so this module's classes cannot be "
                        + "enumerated and the check would pass on the empty set")
                .isNotNull();
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("code source of KafkaJfrEventCatalogue is not a usable path", e);
        }
    }

    /**
     * The binary name of a class file, from its path relative to the output directory.
     *
     * @param relative the class file's path under {@link #CLASSES}
     * @return the binary name, {@code $} for nesting included
     */
    private static String binaryName(Path relative) {
        StringBuilder name = new StringBuilder(relative.toString().length());
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(relative.getName(i));
        }
        // Stripped as a suffix, not replaced everywhere: a package segment may legitimately contain
        // the characters ".class".
        return name.substring(0, name.length() - CLASS_SUFFIX.length());
    }

    private static Set<String> declaredEventClasses() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(CLASSES)) {
            for (Path classFile : tree.filter(p -> p.toString().endsWith(CLASS_SUFFIX)).toList()) {
                String name = binaryName(CLASSES.relativize(classFile));
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
}
