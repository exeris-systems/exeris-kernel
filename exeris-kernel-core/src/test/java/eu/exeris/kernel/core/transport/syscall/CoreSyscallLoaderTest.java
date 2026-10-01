/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Smoke Tests: {@link CoreSyscallLoader} — load(Arena) and platform invariants.
 *
 * <h2>Design</h2>
 * <p>{@link CoreSyscallLoader#load(Arena)} is invoked once in {@link #setUp()} with
 * {@code Arena.global()} — the same arena the Community bootstrapper will use.
 * This validates the full resolution path without a running kernel.
 *
 * @since 0.5.0
 */
@DisplayName("L1 Smoke: CoreSyscallLoader — load(Arena) and platform invariants")
@EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
class CoreSyscallLoaderTest {

    private static SyscallHandles handles;

    @BeforeAll
    static void setUp() {
        handles = CoreSyscallLoader.load(Arena.global()); //NOPMD — test harness uses Arena.global(); production code receives arena from bootstrapper
    }

    // =========================================================================
    // Nested: Static Initialization Smoke
    // =========================================================================

    @Nested
    @DisplayName("load(Arena) succeeds")
    class LoadSucceeds {

        @Test
        @DisplayName("load(Arena.global()) returns non-null SyscallHandles")
        void handlesNonNull() {
            assertThat(handles).isNotNull();
        }
    }

    // =========================================================================
    // Nested: POSIX Platform (Linux / macOS)
    // =========================================================================

    @Nested
    @DisplayName("POSIX platform (Linux / macOS)")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    class PosixPlatform {

        @Test
        @DisplayName("POSIX: core socket handles (socket, bind, listen, accept, connect, close) are non-null")
        void coreHandlesNonNull() {
            assertThat(handles.socket()).as("socket").isNotNull();
            assertThat(handles.bind()).as("bind").isNotNull();
            assertThat(handles.listen()).as("listen").isNotNull();
            assertThat(handles.accept()).as("accept").isNotNull();
            assertThat(handles.connect()).as("connect").isNotNull();
            assertThat(handles.close()).as("close").isNotNull();
        }

        @Test
        @DisplayName("POSIX: send and recv handles are non-null")
        void sendRecvNonNull() {
            assertThat(handles.send()).as("send").isNotNull();
            assertThat(handles.recv()).as("recv").isNotNull();
        }

        @Test
        @DisplayName("POSIX: hasFcntl()=true (non-blocking via fcntl)")
        void fcntlPresent() {
            assertThat(handles.hasFcntl()).isTrue();
        }

        @Test
        @DisplayName("POSIX: hasIoctlsocket()=false (Windows-only)")
        void noIoctlsocket() {
            assertThat(handles.hasIoctlsocket()).isFalse();
        }
    }

    // =========================================================================
    // Nested: Windows Platform (Winsock2)
    // =========================================================================

    @Nested
    @DisplayName("Windows platform (Winsock2)")
    @EnabledOnOs(OS.WINDOWS)
    class WindowsPlatform {

        @Test
        @DisplayName("Windows: core socket handles (socket, bind, listen, accept, connect) are non-null")
        void coreHandlesNonNull() {
            assertThat(handles.socket()).as("socket").isNotNull();
            assertThat(handles.bind()).as("bind").isNotNull();
            assertThat(handles.listen()).as("listen").isNotNull();
            assertThat(handles.accept()).as("accept").isNotNull();
            assertThat(handles.connect()).as("connect").isNotNull();
        }

        @Test
        @DisplayName("Windows: closesocket handle is non-null (mapped to close field)")
        void closesocketNonNull() {
            assertThat(handles.close()).as("closesocket").isNotNull();
        }

        @Test
        @DisplayName("Windows: send and recv handles are non-null")
        void sendRecvNonNull() {
            assertThat(handles.send()).as("send").isNotNull();
            assertThat(handles.recv()).as("recv").isNotNull();
        }

        @Test
        @DisplayName("Windows: hasIoctlsocket()=true (non-blocking via ioctlsocket)")
        void ioctlsocketPresent() {
            assertThat(handles.hasIoctlsocket()).isTrue();
        }

        @Test
        @DisplayName("Windows: hasFcntl()=false (POSIX-only)")
        void noFcntl() {
            assertThat(handles.hasFcntl()).isFalse();
        }
    }

    // =========================================================================
    // Nested: Cross-platform invariant
    // =========================================================================

    @Nested
    @DisplayName("Cross-platform invariant")
    class CrossPlatformInvariant {

        @Test
        @DisplayName("Exactly one of fcntl / ioctlsocket is present — never both, never neither")
        void exactlyOneNonBlockingControl() {
            assertThat(handles.hasFcntl() ^ handles.hasIoctlsocket())
                    .as("Exactly one of hasFcntl/hasIoctlsocket must be true")
                    .isTrue();
        }
    }
}
