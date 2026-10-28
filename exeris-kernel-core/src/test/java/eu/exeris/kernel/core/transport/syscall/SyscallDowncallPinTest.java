/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import eu.exeris.kernel.core.transport.syscall.SyscallDowncall.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the platform and alternatives group of every {@link SyscallDowncall} row. {@link CoreSyscallLoader}
 * loads the {@code POSIX_*} rows on POSIX and the {@code WINDOWS_*} rows on Windows, and
 * {@link SyscallErrno} reads its candidates from the {@code errno} group, so these attributes are what
 * the ABI symbol-resolution suite's manifest rests on.
 */
@DisplayName("L0: SyscallDowncall platform and group pins")
class SyscallDowncallPinTest {

    @Test
    @DisplayName("POSIX_* and errno rows are POSIX, WINDOWS_* rows are WINDOWS")
    void platformFollowsTheRowName() {
        List<String> drift = Arrays.stream(SyscallDowncall.values())
                .filter(row -> row.platform() != expectedPlatform(row))
                .map(row -> row.name() + " is " + row.platform())
                .toList();
        assertThat(drift).as("rows whose platform contradicts their name").isEmpty();
    }

    @Test
    @DisplayName("the errno group is exactly ERRNO_LOCATION and BSD_ERROR")
    void errnoGroupIsExactlyTheTwoAccessors() {
        assertThat(Arrays.stream(SyscallDowncall.values())
                .filter(row -> SyscallDowncall.Group.ERRNO.equals(row.anyOf())).toList())
                .containsExactly(SyscallDowncall.ERRNO_LOCATION, SyscallDowncall.BSD_ERROR);
        assertThat(Arrays.stream(SyscallDowncall.values())
                .filter(row -> row.anyOf() != null && !SyscallDowncall.Group.ERRNO.equals(row.anyOf())).toList())
                .as("no other alternatives group exists").isEmpty();
    }

    private static Platform expectedPlatform(SyscallDowncall row) {
        return row.name().startsWith("WINDOWS_") ? Platform.WINDOWS : Platform.POSIX;
    }
}
