/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The errno of the first failed syscall in a JVM is the syscall's own, not the one left by initialising
 * the errno reader. The class initialises on its first use, so the case runs in a fresh child JVM where
 * nothing has touched it before the first failure.
 */
@DisplayName("SyscallErrno: the first errno read of a JVM belongs to the failed call")
class SyscallErrnoFreshJvmTest {

    @Test
    @DisplayName("a send that fails first in a fresh JVM reports its own errno")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void firstFailedSendReportsItsOwnErrno() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // On the preview line every class is preview-stamped, so the child cannot load them without
        // the flag; it is the only addition to the distributed line's command.
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                SyscallErrnoFreshJvmProbe.class.getName());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).as("child output: " + output).isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}
