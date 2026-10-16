/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Core: Explicit caller-owned loader that resolves Berkeley socket symbols via Project Panama FFM.
 *
 * <h2>Design — Single Explicit Load Path</h2>
 * <p>This class owns <strong>no arena</strong>, creates <strong>no arena</strong>, and enforces
 * <strong>no memory policy</strong>. The only supported entry point is {@link #load(Arena)},
 * with the arena supplied and owned entirely by the caller.
 *
 * <h2>C-type to ValueLayout mapping</h2>
 * <pre>
 *  C type        ValueLayout   Notes
 *  ─────────────────────────────────────────────────────────
 *  int           JAVA_INT
 *  socklen_t     JAVA_INT      typedef unsigned int
 *  size_t        JAVA_LONG     64-bit ABI (LP64 / LLP64)
 *  ssize_t       JAVA_LONG
 *  void*         ADDRESS       Panama lifetime + bounds safety
 *  sockaddr*     ADDRESS       Panama lifetime + bounds safety
 *  socklen_t*    ADDRESS       Panama lifetime + bounds safety
 *  SOCKET(Win)   JAVA_LONG     UINT_PTR = 64-bit on Win64
 *  u_long*       JAVA_LONG     raw address (ioctlsocket arg)
 * </pre>
 *
 * <h2>The Wall (Core Compliance)</h2>
 * <p>Zero knowledge of {@code io_uring}, {@code msghdr}, Enterprise internals, QUIC,
 * or {@code GlobalMemoryArbiter}. Strictly the portable Berkeley socket surface.
 *
 * @since 0.5
 * @see SyscallHandles
 */
public final class CoreSyscallLoader {

    private static final System.Logger LOG =
            System.getLogger(CoreSyscallLoader.class.getName());

    /**
     * OS detection — computed once, constant-folded by JIT.
     */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final String ARENA_MUST_NOT_BE_NULL = "arena must not be null";

    /**
     * Winsock2 version word: MAKEWORD(2, 2) = 0x0202.
     */
    private static final int WINSOCK_VERSION_2_2 = 0x0202;

    private CoreSyscallLoader() {
        // static utility class
    }

    /**
     * Loads Berkeley socket symbols into {@code arena} and returns resolved handles.
     *
     * <p>This is the single supported loading path. The supplied arena remains fully
     * caller-owned. On Windows this method also invokes {@code WSAStartup(MAKEWORD(2,2),
     * &wsaData)} and allocates the required {@code WSADATA} scratch buffer within that same
     * arena. On POSIX, the arena is still required for API symmetry and caller-owned policy,
     * even though symbol resolution currently uses the native default lookup.
     *
     * @param arena arena whose scope governs the caller-selected loading policy
     * @return immutable {@link SyscallHandles} record containing all resolved handles
     * @throws IllegalStateException if a required symbol is missing or WSAStartup fails
     */
    public static SyscallHandles load(Arena arena) {
        Objects.requireNonNull(arena, ARENA_MUST_NOT_BE_NULL);
        return IS_WINDOWS ? loadWindows(arena) : loadPosix(arena);
    }

    // =========================================================================
    // POSIX (Linux / macOS) loader
    // =========================================================================

    private static SyscallHandles loadPosix(Arena arena) {
        Objects.requireNonNull(arena, ARENA_MUST_NOT_BE_NULL);

        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] Resolving POSIX Berkeley socket symbols via FFM defaultLookup");

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();

        MethodHandle socket = req(linker, lookup, SyscallDowncall.POSIX_SOCKET);
        MethodHandle bind = req(linker, lookup, SyscallDowncall.POSIX_BIND);
        MethodHandle listen = req(linker, lookup, SyscallDowncall.POSIX_LISTEN);
        MethodHandle accept = req(linker, lookup, SyscallDowncall.POSIX_ACCEPT);
        MethodHandle connect = req(linker, lookup, SyscallDowncall.POSIX_CONNECT);
        MethodHandle close = req(linker, lookup, SyscallDowncall.POSIX_CLOSE);
        MethodHandle send = req(linker, lookup, SyscallDowncall.POSIX_SEND);
        MethodHandle recv = req(linker, lookup, SyscallDowncall.POSIX_RECV);
        MethodHandle fcntl = req(linker, lookup, SyscallDowncall.POSIX_FCNTL);

        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] POSIX socket handles resolved successfully");

        return new SyscallHandles(
                socket, bind, listen, accept, connect, close,
                send, recv,
                fcntl,  // POSIX non-blocking control
                null,   // ioctlsocket — Windows only
                null,   // WSAGetLastError — Windows only
                null);  // wsaCleanup — Windows only
    }

    // =========================================================================
    // Windows (Winsock2) loader
    // =========================================================================

    private static SyscallHandles loadWindows(Arena arena) {
        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] Resolving Winsock2 socket symbols via FFM (Ws2_32.dll)");

        Linker linker = Linker.nativeLinker();

        SymbolLookup ws2 = tryLoadLibrary("Ws2_32.dll", arena);
        if (ws2 == null) {
            ws2 = tryLoadLibrary("ws2_32.dll", arena);
        }
        if (ws2 == null) {
            throw new IllegalStateException(
                    "[CoreSyscallLoader] Failed to load Ws2_32.dll — Winsock2 unavailable.");
        }

        invokeWsaStartup(linker, ws2, arena);

        MethodHandle socket = req(linker, ws2, SyscallDowncall.WINDOWS_SOCKET);
        MethodHandle bind = req(linker, ws2, SyscallDowncall.WINDOWS_BIND);
        MethodHandle listen = req(linker, ws2, SyscallDowncall.WINDOWS_LISTEN);
        MethodHandle accept = req(linker, ws2, SyscallDowncall.WINDOWS_ACCEPT);
        MethodHandle connect = req(linker, ws2, SyscallDowncall.WINDOWS_CONNECT);
        MethodHandle close = req(linker, ws2, SyscallDowncall.WINDOWS_CLOSESOCKET);

        java.lang.invoke.MethodType sendRecvType = java.lang.invoke.MethodType.methodType(
                long.class, long.class, MemorySegment.class, int.class, int.class);

        MethodHandle sendRaw = req(linker, ws2, SyscallDowncall.WINDOWS_SEND);
        MethodHandle send = java.lang.invoke.MethodHandles.explicitCastArguments(sendRaw, sendRecvType);

        MethodHandle recvRaw = req(linker, ws2, SyscallDowncall.WINDOWS_RECV);
        MethodHandle recv = java.lang.invoke.MethodHandles.explicitCastArguments(recvRaw, sendRecvType);

        MethodHandle ioctlsocket = req(linker, ws2, SyscallDowncall.WINDOWS_IOCTLSOCKET);
        MethodHandle wsaGetLastError = req(linker, ws2, SyscallDowncall.WINDOWS_WSA_GET_LAST_ERROR);
        MethodHandle wsaCleanup = req(linker, ws2, SyscallDowncall.WINDOWS_WSA_CLEANUP);

        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] Winsock2 handles resolved successfully");

        return new SyscallHandles(
                socket, bind, listen, accept, connect, close,
                send, recv,
                null,             // fcntl — POSIX only
                ioctlsocket,      // Windows non-blocking control
                wsaGetLastError,  // Windows socket error inspection
                wsaCleanup);      // Windows Winsock lifecycle
    }

    // =========================================================================
    // WSAStartup helper
    // =========================================================================

    /**
     * Invokes {@code WSAStartup(MAKEWORD(2,2), &wsaData)}.
     *
     * <p>The WSADATA scratch buffer is allocated in the caller-supplied {@code arena}.
     * This is a one-time bootstrap cost (408 bytes), not a hot-path allocation.
     * The caller — Community bootstrapper or Enterprise arbiter partition — owns the
     * lifetime and accounting of this allocation, consistent with the MemoryAllocator
     * policy for infrastructure-tier memory.
     *
     * @param linker the native linker
     * @param ws2    the Ws2_32.dll symbol lookup
     * @param arena  the arena that owns the WSADATA scratch buffer
     */
    private static void invokeWsaStartup(Linker linker, SymbolLookup ws2, Arena arena) {
        MethodHandle wsaStartup = req(linker, ws2, SyscallDowncall.WINDOWS_WSA_STARTUP);

        // 408 bytes covers all known Win32/Win64 WSADATA struct sizes.
        // Allocated via the caller-supplied arena — memory policy is the caller's concern.
        MemorySegment wsaData = arena.allocate(408, 8);

        int result = wsaStartupCall(wsaStartup, wsaData);
        if (result != 0) {
            throw new IllegalStateException(
                    "[CoreSyscallLoader] WSAStartup failed with error code: " + result
                            + ". Winsock2 is unavailable.");
        }
        LOG.log(System.Logger.Level.DEBUG, "[CoreSyscallLoader] WSAStartup(2.2) succeeded");
    }

    private static int wsaStartupCall(MethodHandle handle, MemorySegment wsaData) {
        try {
            return (int) handle.invokeExact(WINSOCK_VERSION_2_2, wsaData.address());
        } catch (Throwable ex) { //NOPMD AvoidCatchingThrowable — MethodHandle.invokeExact bootstrap isolation
            throw new IllegalStateException(
                    "[CoreSyscallLoader] WSAStartup native call failed", ex);
        }
    }

    // =========================================================================
    // Library load helper
    // =========================================================================

    private static SymbolLookup tryLoadLibrary(String libraryName, Arena arena) {
        try {
            return SymbolLookup.libraryLookup(libraryName, arena);
        } catch (IllegalArgumentException ex) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[CoreSyscallLoader] Library not found: {0} ({1})",
                    libraryName, ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Handle factory
    // =========================================================================

    private static MethodHandle req(Linker linker, SymbolLookup lookup, SyscallDowncall downcall) {
        Optional<MemorySegment> addr = lookup.find(downcall.symbol());
        if (addr.isEmpty()) {
            throw new IllegalStateException(
                    "[CoreSyscallLoader] Required socket symbol not found: '" + downcall.symbol() + "'.");
        }
        return linker.downcallHandle(addr.get(), downcall.descriptor());
    }
}
