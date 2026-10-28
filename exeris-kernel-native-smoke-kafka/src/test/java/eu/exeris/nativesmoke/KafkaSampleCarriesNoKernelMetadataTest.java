/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard test ensuring the kafka smoke sample carries no reachability metadata or GraalVM imports.
 */
class KafkaSampleCarriesNoKernelMetadataTest {

    @Test
    void sampleResourcesContainNoNativeImageDirectory() {
        Path resourceDir = Path.of("src/main/resources/META-INF/native-image");
        assertThat(resourceDir)
                .as("Directory src/main/resources/META-INF/native-image must not exist in kafka smoke sample")
                .doesNotExist();
    }

    @Test
    void nativeMavenPluginConfigHasNoBuildOrJvmArgs() throws IOException {
        Path pomPath = Path.of("pom.xml");
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
}
