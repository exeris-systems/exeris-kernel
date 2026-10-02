/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: {@link KernelVersion} reads the Maven-filtered version stamp.
 *
 * <p>The build filters {@code kernel.version=${project.version}} into
 * {@code target/classes/exeris-kernel.properties}, so within the test classpath
 * the resolved version must be the real POM version — never the literal
 * placeholder and never the {@code "unknown"} fallback.
 *
 * @since 0.8.1
 */
@DisplayName("L1 Unit: KernelVersion (POM-sourced version stamp)")
class KernelVersionTest {

    @Test
    @DisplayName("current() resolves the filtered POM version, not the fallback or placeholder")
    void currentResolvesFilteredVersion() {
        String version = KernelVersion.current();

        assertThat(version)
                .isNotBlank()
                .isNotEqualTo("unknown")
                .doesNotContain("${")
                // Maven semantic version: major.minor.patch with optional -QUALIFIER.
                .matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?");
    }
}
