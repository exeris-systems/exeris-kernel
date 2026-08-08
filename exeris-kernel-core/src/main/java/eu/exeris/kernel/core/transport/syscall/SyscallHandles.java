/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.invoke.MethodHandle;

/**
 * Zero-allocation value carrier holding pre-resolved {@link MethodHandle} instances
 * for raw Berkeley socket C functions.
 *
 * <h2>Valhalla-Ready (JEP 401)</h2>
 * <p>This is a standard {@code record} whose fields are all identity-free.
 * No {@code ==}, no {@code synchronized}, no {@code System.identityHashCode()} — it
 * scalarizes cleanly via C2 JIT Escape Analysis on the hot path.
 * Will be migrated to {@code value record} once JEP 401 is mainline.
 *
 * <h2>C-type to ValueLayout mapping</h2>
 * <pre>
 *  C type          Java type       ValueLayout
 *  ───────────────────────────────────────────────────────────────
 *  int             int             JAVA_INT
 *  size_t          long            JAVA_LONG    (64-bit ABI)
 *  ssize_t         long            JAVA_LONG
 *  socklen_t       int             JAVA_INT
 *  sockaddr *      MemorySegment   ADDRESS      (Panama lifetime + bounds safety)
 *  void *          MemorySegment   ADDRESS      (Panama lifetime + bounds safety)
 *  socklen_t *     MemorySegment   ADDRESS      (Panama lifetime + bounds safety)
 *  SOCKET (Win32)  long            JAVA_LONG    (UINT_PTR = 64-bit on Win64)
 *  u_long *        long            JAVA_LONG    (raw address — ioctlsocket arg)
 * </pre>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This record lives in {@code exeris-kernel-core} and is 100% agnostic of Enterprise
 * internals ({@code io_uring}, {@code msghdr}, QUIC, etc.). It models strictly the
 * portable POSIX/Windows Berkeley socket surface.
 *
 * <h2>Null-handle convention</h2>
 * <p>Platform-specific handles that are absent on the current OS (e.g.
 * {@link #ioctlsocket()} on Linux/macOS, {@link #fcntl()} on Windows) will be
 * {@code null}. Callers MUST guard with a null-check before invoking.
 *
 * @param socket      {@code int socket(int domain, int type, int protocol)}
 * @param bind        {@code int bind(int sockfd, const sockaddr*, socklen_t)}
 * @param listen      {@code int listen(int sockfd, int backlog)}
 * @param accept      {@code int accept(int sockfd, sockaddr*, socklen_t*)}
 * @param connect     {@code int connect(int sockfd, const sockaddr*, socklen_t)}
 * @param close       {@code int close(int fd)} on POSIX /
 *                    {@code int closesocket(SOCKET)} on Windows
 * @param send        {@code ssize_t send(int sockfd, const void*, size_t, int)}
 * @param recv        {@code ssize_t recv(int sockfd, void*, size_t, int)}
 * @param fcntl           {@code int fcntl(int fd, int cmd, ...)} — POSIX only; {@code null} on Windows
 * @param ioctlsocket     {@code int ioctlsocket(SOCKET, long, u_long*)} — Windows only; {@code null} on POSIX
 * @param socketLastError {@code int WSAGetLastError(void)} — Windows only; {@code null} on POSIX
 * @param wsaCleanup      {@code int WSACleanup(void)} — Windows only; {@code null} on POSIX.
 *                        Must be invoked to pair with {@code WSAStartup} before the owning arena is closed.
 * @since 0.5.0
 */
public value record SyscallHandles(
        MethodHandle socket,
        MethodHandle bind,
        MethodHandle listen,
        MethodHandle accept,
        MethodHandle connect,
        MethodHandle close,
        MethodHandle send,
        MethodHandle recv,
        MethodHandle fcntl,
        MethodHandle ioctlsocket,
        MethodHandle socketLastError,
        MethodHandle wsaCleanup
) {

    /**
     * Returns {@code true} if non-blocking control is available through the
     * POSIX {@code fcntl} interface (Linux / macOS).
     *
     * @return {@code true} when {@link #fcntl()} is non-null
     */
    public boolean hasFcntl() {
        return fcntl != null;
    }

    /**
     * Returns {@code true} if non-blocking control is available through the
     * Windows {@code ioctlsocket} interface.
     *
     * @return {@code true} when {@link #ioctlsocket()} is non-null
     */
    public boolean hasIoctlsocket() {
        return ioctlsocket != null;
    }

    /**
     * Returns {@code true} when the plain Berkeley socket send/recv seam is fully available.
     *
     * @return {@code true} when the resolved handles can drive plain socket I/O
     */
    public boolean supportsPlainSocketIo() {
        return send != null && recv != null && close != null;
    }

    /**
     * Returns {@code true} if the Windows {@code WSAGetLastError} handle is available.
     *
     * @return {@code true} when {@link #socketLastError()} is non-null
     */
    public boolean hasSocketLastError() {
        return socketLastError != null;
    }

    /**
     * Returns {@code true} if the Windows {@code WSACleanup} handle is available.
     * Must be invoked before the owning arena is closed to release Winsock resources.
     *
     * @return {@code true} when {@link #wsaCleanup()} is non-null
     */
    public boolean hasWsaCleanup() {
        return wsaCleanup != null;
    }
}
