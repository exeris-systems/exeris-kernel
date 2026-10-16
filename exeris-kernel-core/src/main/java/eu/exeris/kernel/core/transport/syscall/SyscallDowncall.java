/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.FunctionDescriptor;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Descriptor table for all POSIX, Windows, and errno syscall downcalls used by Core.
 *
 * <p>Descriptors are built once during enum class initialisation with fixed layouts. No native linker
 * is queried, keeping initialization exception-free.
 *
 * @since 0.13
 */
enum SyscallDowncall {

    POSIX_SOCKET("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
    POSIX_BIND("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
    POSIX_LISTEN("listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)),
    POSIX_ACCEPT("accept", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
    POSIX_CONNECT("connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
    POSIX_CLOSE("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
    POSIX_SEND("send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)),
    POSIX_RECV("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)),
    POSIX_FCNTL("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)),

    WINDOWS_WSA_STARTUP("WSAStartup", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG)),
    WINDOWS_SOCKET("socket", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT)),
    WINDOWS_BIND("bind", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)),
    WINDOWS_LISTEN("listen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
    WINDOWS_ACCEPT("accept", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS)),
    WINDOWS_CONNECT("connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)),
    WINDOWS_CLOSESOCKET("closesocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    WINDOWS_SEND("send", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT)),
    WINDOWS_RECV("recv", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT)),
    WINDOWS_IOCTLSOCKET("ioctlsocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    WINDOWS_WSA_GET_LAST_ERROR("WSAGetLastError", FunctionDescriptor.of(JAVA_INT)),
    WINDOWS_WSA_CLEANUP("WSACleanup", FunctionDescriptor.of(JAVA_INT)),

    ERRNO_LOCATION("__errno_location", FunctionDescriptor.of(ADDRESS)),
    BSD_ERROR("__error", FunctionDescriptor.of(ADDRESS));

    private final String symbol;
    private final FunctionDescriptor descriptor;

    SyscallDowncall(String symbol, FunctionDescriptor descriptor) {
        this.symbol = symbol;
        this.descriptor = descriptor;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
    }
}
