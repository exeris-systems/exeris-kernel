/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Core: Bootstrap loader that resolves Berkeley socket symbols via Project Panama FFM.
 *
 * <h2>Design — No State, No Arena Policy</h2>
 * <p>This class owns <strong>no arena</strong> and enforces <strong>no memory policy</strong>.
 * The caller decides where native symbols live:
 * <ul>
 *   <li><b>Community:</b> pass {@link Arena#global()} — symbols live for the JVM lifetime.</li>
 *   <li><b>Enterprise:</b> pass an {@link Arena} carved from the
 *       {@code GlobalMemoryArbiter.INFRASTRUCTURE} partition — symbols live inside the
 *       single pre-allocated mmap block.</li>
 * </ul>
 *
 * <h2>C-type to ValueLayout mapping</h2>
 * <pre>
 *  C type        ValueLayout   Notes
 *  ─────────────────────────────────────────────────────────
 *  int           JAVA_INT
 *  socklen_t     JAVA_INT      typedef unsigned int
 *  size_t        JAVA_LONG     64-bit ABI (LP64 / LLP64)
 *  ssize_t       JAVA_LONG
 *  void*         JAVA_LONG     raw address, zero-copy
 *  sockaddr*     JAVA_LONG     raw address, zero-copy
 *  socklen_t*    JAVA_LONG     raw address, zero-copy
 *  SOCKET(Win)   JAVA_LONG     UINT_PTR = 64-bit on Win64
 *  u_long*       JAVA_LONG     raw address (ioctlsocket arg)
 * </pre>
 *
 * <h2>The Wall (Core Compliance)</h2>
 * <p>Zero knowledge of {@code io_uring}, {@code msghdr}, Enterprise internals, QUIC,
 * or {@code GlobalMemoryArbiter}. Strictly the portable Berkeley socket surface.
 *
 * @see SyscallHandles
 * @since 0.5.0
 */
public final class CoreSyscallLoader {

    private static final System.Logger LOG =
            System.getLogger(CoreSyscallLoader.class.getName());

    /**
     * OS detection — computed once, constant-folded by JIT.
     */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

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
     * <p><b>Community:</b> pass {@code Arena.global()}.
     * <b>Enterprise:</b> pass an {@link Arena} whose scope covers a slab from
     * {@code GlobalMemoryArbiter.INFRASTRUCTURE}.
     *
     * <p>On Windows this method also invokes {@code WSAStartup(MAKEWORD(2,2), &wsaData)}.
     * The {@code wsaData} scratch buffer is allocated within the supplied {@code arena}
     * — the 408-byte cost is a one-time bootstrap overhead, not a hot-path allocation.
     *
     * @param arena arena whose scope governs the lifetime of the loaded symbols
     *              (Windows only: used for {@code Ws2_32.dll} library lookup and
     *              {@code WSADATA} scratch allocation; ignored on POSIX as standard
     *              socket libraries are globally resident)
     * @return immutable {@link SyscallHandles} record containing all resolved handles
     * @throws IllegalStateException if a required symbol is missing or WSAStartup fails
     */
    public static SyscallHandles load(Arena arena) {
        Objects.requireNonNull(arena, "arena must not be null");
        return IS_WINDOWS ? loadWindows(arena) : loadPosix();
    }

    // =========================================================================
    // POSIX (Linux / macOS) loader
    // =========================================================================

    private static SyscallHandles loadPosix() {
        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] Resolving POSIX Berkeley socket symbols via FFM defaultLookup");

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();

        MethodHandle socket = req(linker, lookup, "socket",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

        MethodHandle bind = req(linker, lookup, "bind",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT));

        MethodHandle listen = req(linker, lookup, "listen",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

        MethodHandle accept = req(linker, lookup, "accept",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));

        MethodHandle connect = req(linker, lookup, "connect",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT));

        MethodHandle close = req(linker, lookup, "close",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        MethodHandle send = req(linker, lookup, "send",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));

        MethodHandle recv = req(linker, lookup, "recv",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));

        MethodHandle fcntl = req(linker, lookup, "fcntl",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] POSIX socket handles resolved successfully");

        return new SyscallHandles(
                socket, bind, listen, accept, connect, close,
                send, recv,
                fcntl,  // POSIX non-blocking control
                null,   // ioctlsocket — Windows only
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

        MethodHandle socket = req(linker, ws2, "socket",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));

        MethodHandle bind = req(linker, ws2, "bind",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));

        MethodHandle listen = req(linker, ws2, "listen",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));

        MethodHandle accept = req(linker, ws2, "accept",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

        MethodHandle connect = req(linker, ws2, "connect",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));

        MethodHandle close = req(linker, ws2, "closesocket",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

        MethodHandle send = req(linker, ws2, "send",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));

        MethodHandle recv = req(linker, ws2, "recv",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));

        MethodHandle ioctlsocket = req(linker, ws2, "ioctlsocket",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));

        MethodHandle wsaCleanup = req(linker, ws2, "WSACleanup",
                FunctionDescriptor.of(JAVA_INT));

        LOG.log(System.Logger.Level.INFO,
                "[CoreSyscallLoader] Winsock2 handles resolved successfully");

        return new SyscallHandles(
                socket, bind, listen, accept, connect, close,
                send, recv,
                null,          // fcntl — POSIX only
                ioctlsocket,   // Windows non-blocking control
                wsaCleanup);   // Windows Winsock lifecycle
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
        MethodHandle wsaStartup = req(linker, ws2, "WSAStartup",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));

        // 408 bytes covers all known Win32/Win64 WSADATA struct sizes.
        // Allocated via the caller-supplied arena — memory policy is the caller's concern.
        MemorySegment wsaData = arena.allocate(408);

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

    private static MethodHandle req(Linker linker, SymbolLookup lookup,
                                    String sym, FunctionDescriptor desc) {
        Optional<MemorySegment> addr = lookup.find(sym);
        if (addr.isEmpty()) {
            throw new IllegalStateException(
                    "[CoreSyscallLoader] Required socket symbol not found: '" + sym + "'.");
        }
        return linker.downcallHandle(addr.get(), desc);
    }
}
