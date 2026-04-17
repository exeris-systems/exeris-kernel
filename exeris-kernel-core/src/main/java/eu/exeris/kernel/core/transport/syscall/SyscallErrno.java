/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Core helper for minimal POSIX errno inspection on retryable Berkeley socket paths.
 *
 * @since 0.5.0
 */
public final class SyscallErrno {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private static final int EINTR = 4;
    private static final int EAGAIN = 11;
    private static final int EWOULDBLOCK_MAC = 35;
    private static final int WSAEINTR = 10_004;
    private static final int WSAEWOULDBLOCK = 10_035;
    private static final int WSAEINPROGRESS = 10_036;

    private static final MethodHandle ERRNO_LOCATION = resolveErrnoLocation();

    private SyscallErrno() {
    }

    /**
     * Reads the current POSIX {@code errno} value when available.
     *
     * @return current errno, or {@code -1} when unavailable on this runtime
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static int currentPosixErrno() {
        if (IS_WINDOWS || ERRNO_LOCATION == null) {
            return -1;
        }
        try {
            MemorySegment location = (MemorySegment) ERRNO_LOCATION.invokeExact();
            if (location == null || MemorySegment.NULL.equals(location)) {
                return -1;
            }
            return location.reinterpret(JAVA_INT.byteSize()).get(JAVA_INT, 0L);
        } catch (Throwable _) { 
                // FFM invokeExact declares Throwable
            return -1;
        }
    }

    /**
     * Reads the current cross-platform socket error value when available.
     *
     * @param handles resolved Core socket seam handles
     * @return current socket error, or {@code -1} when unavailable on this runtime
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static int currentSocketErrno(SyscallHandles handles) {
        if (!IS_WINDOWS) {
            return currentPosixErrno();
        }
        if (handles == null || !handles.hasSocketLastError()) {
            return -1;
        }
        try {
            return (int) handles.socketLastError().invokeExact();
        } catch (Throwable _) {
            return -1;
        }
    }

    /**
     * Returns whether the errno denotes a retryable non-fatal socket condition.
     *
     * @param errno current socket errno / last-error value
     * @return {@code true} for retryable results such as would-block, interrupted syscall,
     * or temporarily unavailable errno state on the non-blocking hot path
     */
    public static boolean isRetryable(int errno) {
        return errno <= 0 || errno == EINTR || errno == WSAEINTR || errno == WSAEINPROGRESS || isWouldBlock(errno);
    }

    /**
     * Returns whether the errno denotes a non-blocking would-block condition.
     *
     * @param errno current POSIX errno
     * @return {@code true} for EAGAIN/EWOULDBLOCK
     */
    public static boolean isWouldBlock(int errno) {
        return errno == EAGAIN || errno == EWOULDBLOCK_MAC || errno == WSAEWOULDBLOCK;
    }

    private static MethodHandle resolveErrnoLocation() {
        if (IS_WINDOWS) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            String symbol = IS_MAC ? "__error" : "__errno_location";
            return lookup.find(symbol)
                    .map(address -> linker.downcallHandle(address, FunctionDescriptor.of(ADDRESS)))
                    .orElse(null);
        } catch (UnsupportedOperationException | IllegalCallerException | SecurityException | LinkageError _) {
            return null;
        }
    }
}
