/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.ManifestLocator.ManifestSource;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ManifestLocator: the classpath holds at most one license manifest")
class ManifestLocatorTest {

    private static final String CLASSPATH_SOURCE = "classpath:license-manifest.json";

    @TempDir
    Path tmp;

    private Path root(String name, String manifestContent) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        if (manifestContent != null) {
            Files.writeString(root.resolve(ManifestLocator.DEFAULT_MANIFEST_FILENAME), manifestContent);
        }
        return root;
    }

    private static URLClassLoader loader(ClassLoader parent, Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, parent);
    }

    private ManifestSource resolveOnClasspath(ClassLoader loader) {
        return ManifestLocator.resolve(Optional.empty(), tmp.resolve("absent-default.json"), loader);
    }

    @Test
    @DisplayName("No manifest on the classpath: null")
    void zero() throws IOException {
        try (URLClassLoader loader = loader(null, root("a", null))) {
            assertThat(resolveOnClasspath(loader)).isNull();
        }
    }

    @Test
    @DisplayName("One manifest on the classpath: it is read, with the classpath source")
    void one() throws IOException {
        try (URLClassLoader loader = loader(null, root("a", "{\"only\":true}"), root("b", null))) {
            ManifestSource source = resolveOnClasspath(loader);
            assertThat(source.source()).isEqualTo(CLASSPATH_SOURCE);
            assertThat(new String(source.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"only\":true}");
        }
    }

    @Test
    @DisplayName("Two manifests on the classpath: EX-LIC-0001, rawArgs unchanged, message names both URLs")
    void two() throws IOException {
        Path a = root("a", "{\"n\":1}");
        Path b = root("b", "{\"n\":2}");
        String urlA = manifestUrl(a);
        String urlB = manifestUrl(b);
        try (URLClassLoader loader = loader(null, a, b)) {
            assertThatThrownBy(() -> resolveOnClasspath(loader))
                    .isInstanceOfSatisfying(ContractBreachException.class, breach -> {
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                        assertThat(breach.rawArgs()).containsExactly(CLASSPATH_SOURCE);
                        assertThat(breach.getMessage())
                                .contains(urlA)
                                .contains(urlB)
                                .contains(ContractBootstrapStep.MANIFEST_PATH_KEY);
                    });
        }
    }

    @Test
    @DisplayName("One manifest seen twice through a loader chain counts once")
    void sameUrlThroughDelegationCountsOnce() throws IOException {
        Path a = root("a", "{\"n\":1}");
        try (URLClassLoader parent = loader(null, a);
                URLClassLoader child = loader(parent, a)) {
            assertThat(Collections.list(child.getResources(ManifestLocator.DEFAULT_MANIFEST_FILENAME)))
                    .as("the chain reports the same URL twice, so the control exercises the de-duplication")
                    .hasSize(2);
            ManifestSource source = resolveOnClasspath(child);
            assertThat(new String(source.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}");
        }
    }

    @Test
    @DisplayName("A configured path is read without consulting the classpath, so two classpath manifests do not matter")
    void configuredPathBypassesTheClasspath() throws IOException {
        Path configured = root("configured", "{\"configured\":true}");
        try (URLClassLoader loader = loader(null, root("a", "{\"n\":1}"), root("b", "{\"n\":2}"))) {
            ManifestSource source = ManifestLocator.resolve(
                    Optional.of(configured.resolve(ManifestLocator.DEFAULT_MANIFEST_FILENAME).toString()),
                    tmp.resolve("absent-default.json"),
                    loader);
            assertThat(new String(source.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"configured\":true}");
        }
    }

    private static String manifestUrl(Path root) throws IOException {
        return root.resolve(ManifestLocator.DEFAULT_MANIFEST_FILENAME).toUri().toURL().toExternalForm();
    }
}
