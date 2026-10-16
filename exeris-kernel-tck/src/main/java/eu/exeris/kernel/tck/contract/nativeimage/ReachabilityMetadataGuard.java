/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test guard utilities verifying committed {@code reachability-metadata.json} golden files against
 * declared runtime models, computing JFR enclosing class closures, and reflecting static constants.
 *
 * @since 0.13
 */
public final class ReachabilityMetadataGuard {

    private ReachabilityMetadataGuard() {
        // Static guard utility.
    }

    /**
     * Asserts that the committed {@code reachability-metadata.json} resource matches the given metadata model.
     *
     * <p>On mismatch or missing resource, the expected content is written to
     * {@code target/native-image-expected/<artifactId>/reachability-metadata.json} and the test fails
     * with a copy-paste command hint to update the committed file.
     *
     * @param anchor     an anchor class from the module owning the metadata
     * @param artifactId the Maven artifact ID of the module (e.g. {@code "exeris-kernel-core"})
     * @param model      the expected reachability metadata model
     * @throws IOException if writing the expected file fails
     */
    public static void assertCommittedMatches(Class<?> anchor, String artifactId, ReachabilityMetadata model)
            throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(model, "model");

        String expected = model.render();
        String resourcePath = "/META-INF/native-image/eu.exeris/" + artifactId + "/reachability-metadata.json";

        String committed = null;
        try (InputStream in = anchor.getResourceAsStream(resourcePath)) {
            if (in != null) {
                committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        if (Objects.equals(committed, expected)) {
            return;
        }

        Path classesDir = DeclarationSiteScanner.classesDirectory(anchor);
        Path targetDir = classesDir.getParent();
        Path expectedFile = targetDir.resolve("native-image-expected")
                .resolve(artifactId)
                .resolve("reachability-metadata.json");
        Files.createDirectories(expectedFile.getParent());
        Files.writeString(expectedFile, expected, StandardCharsets.UTF_8);

        Path moduleRoot = targetDir.getParent();
        Path sourceFile = moduleRoot.resolve("src/main/resources/META-INF/native-image/eu.exeris")
                .resolve(artifactId)
                .resolve("reachability-metadata.json");

        String hint = String.format(
                "Committed reachability-metadata.json does not match expected model for %s.%n"
                        + "Run to update:%ncp %s %s%n",
                artifactId,
                expectedFile.toAbsolutePath(),
                sourceFile.toAbsolutePath()
        );

        assertThat(committed)
                .withFailMessage(hint)
                .isEqualTo(expected);
    }

    /**
     * Computes the transitive closure of declaring/enclosing classes for a collection of JFR event class names.
     *
     * <p>When JFR event classes are nested within enclosing classes, class loading of the event triggers
     * initialisation of its enclosing class. This method returns the complete set of binary class names
     * required for reachability metadata.
     *
     * @param names  collection of JFR event class binary names
     * @param loader class loader used to inspect class hierarchies
     * @return sorted list of binary class names including all declaring classes
     */
    public static List<String> jfrClosure(Collection<String> names, ClassLoader loader) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(loader, "loader");

        Set<String> closure = new TreeSet<>();
        for (String name : names) {
            closure.add(name);
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                Class<?> enclosing = clazz.getEnclosingClass();
                while (enclosing != null) {
                    closure.add(enclosing.getName());
                    enclosing = enclosing.getEnclosingClass();
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to resolve JFR event class: " + name, e);
            }
        }
        return new ArrayList<>(closure);
    }

    /**
     * Reads the value of a static field from the given class via reflection.
     *
     * @param clazz     class declaring the static field
     * @param fieldName name of the static field
     * @return the value of the static field
     */
    public static Object readStaticField(Class<?> clazz, String fieldName) {
        return readStaticField(clazz, fieldName, Object.class);
    }

    /**
     * Reads the value of a static field from the given class via reflection, casting to the expected type.
     *
     * @param clazz     class declaring the static field
     * @param fieldName name of the static field
     * @param fieldType expected field type class
     * @param <T>       the field type
     * @return the value of the static field
     */
    // The test harness reflects over package-private constants across module boundaries to verify declarations
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    public static <T> T readStaticField(Class<?> clazz, String fieldName, Class<T> fieldType) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(fieldType, "fieldType");
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read static field " + clazz.getName() + "." + fieldName, e);
        }
    }
}
