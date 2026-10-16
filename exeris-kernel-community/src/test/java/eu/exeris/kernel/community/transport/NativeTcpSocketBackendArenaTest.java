/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Community: NativeTcpSocketBackend Arena.global() lifecycle")
@DisabledOnOs(OS.WINDOWS)
class NativeTcpSocketBackendArenaTest {

    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int DEFAULT_IP_PROTOCOL = 0;

    @BeforeEach
    @AfterEach
    void clearBackendOverride() {
        System.clearProperty(NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY);
        System.clearProperty(NativeTcpSocketBackend.SOCKET_BACKEND_ENV_ALIAS);
    }

    @Test
    @DisplayName("close is repeatable and leaves handles usable")
    void closeIsRepeatableAndLeavesHandlesUsable() throws Throwable {
        NativeTcpSocketBackend backend = new NativeTcpSocketBackend();

        assertThat(backend.requestedSocketBackend())
                .as("Requested backend must not be NIO (pinned by property '%s' or env '%s')",
                        NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY,
                        NativeTcpSocketBackend.SOCKET_BACKEND_ENV)
                .isNotEqualTo(NativeTcpSocketBackend.SOCKET_BACKEND_NIO);
        assertThat(backend.activeSocketBackend())
                .as("Active backend must not be NIO (pinned by property '%s' or env '%s')",
                        NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY,
                        NativeTcpSocketBackend.SOCKET_BACKEND_ENV)
                .isNotEqualTo(NativeTcpSocketBackend.SOCKET_BACKEND_NIO);

        SyscallHandles handles = backend.socketHandles();
        assertThat(handles)
                .as("Syscall handles must not be null when backend is not NIO")
                .isNotNull();

        assertThatCode(() -> {
            backend.close();
            backend.close();
        }).as("close() must be repeatable without throwing").doesNotThrowAnyException();

        int fd = (int) handles.socket().invokeExact(AF_INET, SOCK_STREAM, DEFAULT_IP_PROTOCOL);
        try {
            assertThat(fd).as("Socket file descriptor must be non-negative").isGreaterThanOrEqualTo(0);
        } finally {
            if (fd >= 0) {
                int closeResult = (int) handles.close().invokeExact(fd);
                assertThat(closeResult).as("Closing socket file descriptor must return 0").isZero();
            }
        }
    }
}
