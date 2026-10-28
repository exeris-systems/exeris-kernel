/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard test ensuring the sample application carries no kernel reachability metadata and no GraalVM imports.
 */
class SampleCarriesNoKernelMetadataTest {

    @Test
    void sampleResourcesContainNoNativeImageDirectory() {
        Path resourceDir = Path.of("src/main/resources/META-INF/native-image");
        assertThat(resourceDir)
                .as("Directory src/main/resources/META-INF/native-image must not exist")
                .doesNotExist();
    }

    @Test
    void kafkaSampleResourcesContainNoNativeImageDirectory() {
        Path resourceDir = Path.of("../exeris-kernel-native-smoke-kafka/src/main/resources/META-INF/native-image");
        assertThat(resourceDir)
                .as("Directory src/main/resources/META-INF/native-image in kafka module must not exist")
                .doesNotExist();
    }

    @Test
    void nativeMavenPluginConfigHasNoBuildOrJvmArgs() throws IOException {
        assertNoBuildOrJvmArgs(Path.of("pom.xml"));
        assertNoBuildOrJvmArgs(Path.of("../exeris-kernel-native-smoke-kafka/pom.xml"));
    }

    private void assertNoBuildOrJvmArgs(Path pomPath) throws IOException {
        assertThat(pomPath).exists();
        String content = Files.readString(pomPath);
        assertThat(content)
                .as("%s must not configure buildArgs or jvmArgs in native-maven-plugin", pomPath)
                .doesNotContain("<buildArgs>")
                .doesNotContain("<jvmArgs>");
    }

    @Test
    void sourceFilesContainNoGraalvmReferences() throws IOException {
        Path srcDir = Path.of("src");
        if (Files.exists(srcDir)) {
            try (Stream<Path> stream = Files.walk(srcDir)) {
                List<Path> offendingFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(this::containsGraalvmReference)
                        .toList();
                assertThat(offendingFiles)
                        .as("No source file under src/ may reference forbidden graal package")
                        .isEmpty();
            }
        }
    }

    private boolean containsGraalvmReference(Path javaFile) {
        try {
            String content = Files.readString(javaFile);
            String forbidden = String.valueOf(new char[]{'o', 'r', 'g', '.', 'g', 'r', 'a', 'a', 'l', 'v', 'm'});
            return content.contains(forbidden);
        } catch (IOException _) {
            return false;
        }
    }

    @Test
    void generatedRoleCheckRegistryReachabilityMetadataIsEmitted() {
        URL resourceUrl = getClass().getClassLoader().getResource(
                "META-INF/native-image/eu.exeris.kernel.security.generated/role-check-registry/reachability-metadata.json");
        assertThat(resourceUrl)
                .as("Processor-emitted role check registry reachability metadata must be present on the classpath")
                .isNotNull();
    }
}
