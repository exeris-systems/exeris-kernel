/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.AccessFlag;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: the module-wide registry of JEP 401 value carriers.
 *
 * <h2>Why this exists</h2>
 * <p>The {@code value} modifier is the only difference between a carrier on the two distribution
 * lines. Nothing else in the suite notices when it is lost to a merge, a reformat, or a conflict
 * resolution that takes the wrong side — structural equality cases pass for an identity record too.
 * Per-carrier {@code ValhallaReadiness} blocks assert it for a handful of types; this registry
 * covers the rest of the module without one nested block per carrier.
 *
 * <h2>Non-vacuity</h2>
 * <p>A reflective sweep that discovers nothing passes every assertion made over it — that is exactly
 * how {@code ExerisArchitectureTest} came to inspect zero Core and Community classes while reporting
 * green. The discovery floor below is the guard: a broken walk reports zero and reddens instead.
 *
 * <h2>Why the assertion is exhaustive rather than a list</h2>
 * <p>This module's sweep is complete, so the check is stated in the strong direction: <em>every</em>
 * record discovered here must be a value class unless it appears in {@link #IDENTITY_BY_DESIGN} with
 * a reason. A list of converted carriers would have to be edited in step with every conversion and
 * would silently under-report when someone forgot; this form cannot be under-reported, and a newly
 * added record has to be a deliberate decision either way.
 *
 * @since 0.11.0
 */
@DisplayName("L1 Unit: Valhalla value-carrier registry (Kafka)")
class ValhallaValueCarrierRegistryTest {

    /** Only classes under this package are considered ours; vendored entries are ignored. */
    private static final String MODULE_PACKAGE = "eu.exeris.kernel.community.kafka";

    /**
     * Floors, not exact counts: they exist to catch a discovery walk that silently finds nothing,
     * not to track module size. Set well below the current census so ordinary growth never
     * reddens them.
     */
    private static final int CLASS_FLOOR = 10;
    private static final int RECORD_FLOOR = 2;

    /**
     * Records this module deliberately keeps as identity classes, each with the reason.
     *
     * <p>An entry is not a to-do — it is a decision, and the test below asserts such a record has
     * <em>not</em> been converted, so converting one without removing it from this map reddens.
     */
    private static final Map<String, String> IDENTITY_BY_DESIGN = Map.of();

    /** The hand-written {@code value class}es, if any. Empty here — this module has none. */
    private static final List<String> VALUE_CLASSES = List.of();

    // =========================================================================
    // 1. The sweep itself must not be vacuous
    // =========================================================================

    @Nested
    @DisplayName("1. Discovery")
    class Discovery {

        @Test
        @DisplayName("The class-file walk finds this module's classes")
        void walkIsNotVacuous() throws Exception {
            assertThat(discoverClasses())
                    .as("class-file discovery found nothing — the walk is broken, "
                            + "and every assertion made over it would pass vacuously")
                    .hasSizeGreaterThan(CLASS_FLOOR);
        }

        @Test
        @DisplayName("The walk finds this module's records")
        void recordsAreDiscovered() throws Exception {
            assertThat(discoverClasses().stream().filter(Class::isRecord).toList())
                    .as("no records discovered — a carrier-scoped assertion would inspect nothing")
                    .hasSizeGreaterThan(RECORD_FLOOR);
        }
    }

    // =========================================================================
    // 2. Declared carriers carry the modifier
    // =========================================================================

    @Nested
    @DisplayName("2. Every carrier carries the modifier")
    class Carriers {

        @Test
        @DisplayName("Every record in this module is a value class, or is excused by name")
        void everyRecordIsAValueClass() throws Exception {
            List<String> identityRecords = discoverClasses().stream()
                    .filter(Class::isRecord)
                    .filter(type -> !type.isValue())
                    .map(Class::getName)
                    .filter(name -> !IDENTITY_BY_DESIGN.containsKey(name))
                    .sorted()
                    .toList();
            assertThat(identityRecords)
                    .as("these records still carry ACC_IDENTITY; convert them, or record the "
                            + "reason in IDENTITY_BY_DESIGN so the decision is visible")
                    .isEmpty();
        }

        /**
         * The direction that keeps the exclusion map honest: an excused record must actually still
         * be an identity class. Converting one while leaving it excused reddens here, so the map
         * cannot quietly rot into a list of stale to-dos.
         */
        @Test
        @DisplayName("Excused records really are still identity classes")
        void excusedRecordsAreStillIdentityClasses() throws ClassNotFoundException {
            for (Map.Entry<String, String> excused : IDENTITY_BY_DESIGN.entrySet()) {
                assertThat(load(excused.getKey()).isValue())
                        .as("%s is excused because %s — if that no longer holds, "
                                + "convert it and drop the entry", excused.getKey(), excused.getValue())
                        .isFalse();
            }
        }

        /**
         * Interfaces are excluded because they are neither: an interface is not a value class and
         * does not carry {@code ACC_IDENTITY} either, so for them both reads answer "no" without
         * disagreeing about anything.
         */
        @Test
        @DisplayName("accessFlags() agrees with isValue() across the module")
        void accessFlagsAgreeWithIsValue() throws Exception {
            List<String> disagreeing = discoverClasses().stream()
                    .filter(type -> !type.isInterface())
                    .filter(type -> type.isValue() == type.accessFlags().contains(AccessFlag.IDENTITY))
                    .map(Class::getName)
                    .sorted()
                    .toList();
            assertThat(disagreeing)
                    .as("for a class, exactly one of isValue() and ACC_IDENTITY must hold")
                    .isEmpty();
        }

        @Test
        @DisplayName("The hand-written value classes carry the modifier")
        void handWrittenValueClassesCarryTheModifier() throws ClassNotFoundException {
            for (String fqn : VALUE_CLASSES) {
                assertThat(load(fqn).isValue())
                        .as("%s must carry the value modifier; ACC_IDENTITY must be clear", fqn)
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // 3. Controls — the assertion must be able to fail
    // =========================================================================

    @Nested
    @DisplayName("3. Controls")
    class Controls {

        /**
         * Both directions on the running JDK, so a green registry is evidence that
         * {@code isValue()} discriminates rather than evidence that it answers {@code true}
         * to everything. {@code Integer} is one of the migrated platform classes; {@code String}
         * deliberately is not.
         */
        @Test
        @DisplayName("isValue() discriminates on known JDK classes")
        void jdkControlsDiscriminate() {
            assertThat(Integer.class.isValue())
                    .as("Integer is a migrated value class on JDK 28")
                    .isTrue();
            assertThat(String.class.isValue())
                    .as("String is deliberately not a value class")
                    .isFalse();
        }

    }

    // =========================================================================
    // Discovery helper
    // =========================================================================

    private static Class<?> load(String fqn) throws ClassNotFoundException {
        return Class.forName(fqn, false, ValhallaValueCarrierRegistryTest.class.getClassLoader());
    }

    /**
     * Loads every class this module compiled, from the module's own output directory.
     *
     * <p>Classes are loaded with {@code initialize=false} deliberately: running a static
     * initialiser here would load native libraries and open resources that a reflective census
     * has no business touching.
     */
    private static List<Class<?>> discoverClasses() throws Exception {
        Path root = moduleOutputRoot();
        ClassLoader loader = ValhallaValueCarrierRegistryTest.class.getClassLoader();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> toBinaryName(root, path))
                    .filter(name -> name.startsWith(MODULE_PACKAGE))
                    .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                    .map(name -> loadOrNull(name, loader))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private static Path moduleOutputRoot() throws URISyntaxException {
        // Anchored on a class of this module rather than a relative path, so the test does not
        // depend on the working directory Surefire happens to run it from.
        return Path.of(eu.exeris.kernel.community.kafka.KafkaEventConfig.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String toBinaryName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(File.separatorChar, '.');
    }

    private static Class<?> loadOrNull(String binaryName, ClassLoader loader) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | NoClassDefFoundError _) {
            return null;
        }
    }
}
