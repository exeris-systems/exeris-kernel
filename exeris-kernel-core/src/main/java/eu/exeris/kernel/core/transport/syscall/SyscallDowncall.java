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
 * Each row names its platform and, for the errno accessors of which any one suffices, its alternatives group.
 *
 * <p>Descriptors are built once during enum class initialisation with fixed layouts. No native linker
 * is queried, keeping initialization exception-free.
 *
 * @since 0.13
 */
enum SyscallDowncall {

    POSIX_SOCKET("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Platform.POSIX, null),
    POSIX_BIND("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), Platform.POSIX, null),
    POSIX_LISTEN("listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), Platform.POSIX, null),
    POSIX_ACCEPT("accept", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), Platform.POSIX, null),
    POSIX_CONNECT("connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), Platform.POSIX, null),
    POSIX_CLOSE("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), Platform.POSIX, null),
    POSIX_SEND("send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), Platform.POSIX, null),
    POSIX_RECV("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), Platform.POSIX, null),
    POSIX_FCNTL("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), Platform.POSIX, null),

    WINDOWS_WSA_STARTUP("WSAStartup", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG), Platform.WINDOWS, null),
    WINDOWS_SOCKET("socket", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT), Platform.WINDOWS, null),
    WINDOWS_BIND("bind", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT), Platform.WINDOWS, null),
    WINDOWS_LISTEN("listen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT), Platform.WINDOWS, null),
    WINDOWS_ACCEPT("accept", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS), Platform.WINDOWS, null),
    WINDOWS_CONNECT("connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT), Platform.WINDOWS, null),
    WINDOWS_CLOSESOCKET("closesocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG), Platform.WINDOWS, null),
    WINDOWS_SEND("send", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT),
            Platform.WINDOWS, null),
    WINDOWS_RECV("recv", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT),
            Platform.WINDOWS, null),
    WINDOWS_IOCTLSOCKET("ioctlsocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Platform.WINDOWS, null),
    WINDOWS_WSA_GET_LAST_ERROR("WSAGetLastError", FunctionDescriptor.of(JAVA_INT), Platform.WINDOWS, null),
    WINDOWS_WSA_CLEANUP("WSACleanup", FunctionDescriptor.of(JAVA_INT), Platform.WINDOWS, null),

    ERRNO_LOCATION("__errno_location", FunctionDescriptor.of(ADDRESS), Platform.POSIX, Group.ERRNO),
    BSD_ERROR("__error", FunctionDescriptor.of(ADDRESS), Platform.POSIX, Group.ERRNO);

    /** The platform family whose socket API exports a symbol. */
    /* default */ enum Platform {
        /** Resolved from the process's default lookup (libc). */
        POSIX,
        /** Resolved from {@code Ws2_32.dll}. */
        WINDOWS
    }

    private final String symbol;
    private final FunctionDescriptor descriptor;
    private final Platform platform;
    private final String anyOf;

    /**
     * Declares one syscall row.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature
     * @param platform   the platform family that exports the symbol
     * @param anyOf      the name of the alternatives group in which a single resolved member suffices,
     *                   or {@code null} when the symbol is required on its own
     */
    SyscallDowncall(String symbol, FunctionDescriptor descriptor, Platform platform, String anyOf) {
        this.symbol = symbol;
        this.descriptor = descriptor;
        this.platform = platform;
        this.anyOf = anyOf;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
    }

    /* default */ Platform platform() {
        return platform;
    }

    /* default */ String anyOf() {
        return anyOf;
    }

    /** Names of the alternatives groups a row can belong to. */
    /* default */ static final class Group {

        /** The errno accessors: whichever one the platform exports serves. */
        /* default */ static final String ERRNO = "errno";

        private Group() {
            // constants only
        }
    }
}
