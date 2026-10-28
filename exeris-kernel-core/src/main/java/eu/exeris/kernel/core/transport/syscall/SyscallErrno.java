/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Core helper for minimal POSIX errno inspection on retryable Berkeley socket paths.
 *
 * @since 0.5
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
    private static final int EPIPE = 32;
    private static final int ECONNRESET_LINUX = 104;
    private static final int ECONNRESET_MAC = 54;
    private static final int WSAECONNABORTED = 10_053;
    private static final int WSAECONNRESET = 10_054;

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
     * Runs the errno readers once, so that their first use, which initialises this class and links the
     * downcalls, cannot happen between a failed syscall and the read of its errno: that first use
     * resolves symbols and overwrites errno. {@link SyscallHandles} calls it when it is constructed, and
     * a handle is the only way to issue a syscall whose errno is read afterwards.
     *
     * <p>The Windows reader is a handle the caller supplies, so it is passed in; the POSIX reader needs
     * none. Reading is harmless: the value is discarded.
     *
     * @param socketLastError the Windows {@code WSAGetLastError} handle, or {@code null}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static void warmUp(MethodHandle socketLastError) {
        currentPosixErrno();
        if (socketLastError != null) {
            try {
                // the value is meaningless here; the call is what links the downcall
                Objects.requireNonNull(String.valueOf((int) socketLastError.invokeExact()));
            } catch (Throwable _) {
                // a reader that cannot be invoked here is reported by the read that needs it
            }
        }
    }

    /**
     * Returns whether the errno denotes a retryable non-fatal socket condition.
     *
     * @param errno current socket errno / last-error value
     * @return {@code true} for retryable results such as would-block, interrupted syscall,
     *     or temporarily unavailable errno state on the non-blocking hot path
     */
    public static boolean isRetryable(int errno) {
        if (errno <= 0) {
            return false;
        }
        return errno == EINTR || errno == WSAEINTR || errno == WSAEINPROGRESS || isWouldBlock(errno);
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

    /**
     * Returns whether the errno says the peer ended the connection: a send into a connection the
     * peer reset or closed fails with it, and retrying never succeeds.
     *
     * @param errno current socket errno / last-error value
     * @return {@code true} for a broken pipe, or a connection reset or aborted by the peer; {@code false}
     *     for anything else, retryable values and an unavailable errno ({@code <= 0}) included
     */
    public static boolean isPeerReset(int errno) {
        if (IS_WINDOWS) {
            return errno == WSAECONNRESET || errno == WSAECONNABORTED;
        }
        return errno == EPIPE || errno == (IS_MAC ? ECONNRESET_MAC : ECONNRESET_LINUX);
    }

    private static MethodHandle resolveErrnoLocation() {
        if (IS_WINDOWS) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            List<SyscallDowncall> candidates = new ArrayList<>();
            for (SyscallDowncall row : SyscallDowncall.values()) {
                if (SyscallDowncall.Group.ERRNO.equals(row.anyOf())) {
                    candidates.add(row);
                }
            }
            if (IS_MAC) {
                Collections.reverse(candidates);
            }
            for (SyscallDowncall candidate : candidates) {
                MethodHandle handle = lookup.find(candidate.symbol())
                        .map(address -> linker.downcallHandle(address, candidate.descriptor()))
                        .orElse(null);
                if (handle != null) {
                    return handle;
                }
            }
            return null;
        } catch (UnsupportedOperationException | IllegalCallerException | SecurityException | LinkageError _) {
            return null;
        }
    }
}
