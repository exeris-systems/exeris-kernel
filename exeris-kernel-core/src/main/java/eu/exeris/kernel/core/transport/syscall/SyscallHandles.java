/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 *  C type          Java type     ValueLayout
 *  ─────────────────────────────────────────
 *  int             int           JAVA_INT
 *  size_t          long          JAVA_LONG    (64-bit ABI)
 *  ssize_t         long          JAVA_LONG
 *  socklen_t       int           JAVA_INT
 *  sockaddr *      long          JAVA_LONG    (raw pointer as long — zero-copy)
 *  void *          long          JAVA_LONG    (raw pointer as long — zero-copy)
 *  SOCKET (Win32)  long          JAVA_LONG    (UINT_PTR = 64-bit on Win64)
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
 * @param fcntl       {@code int fcntl(int fd, int cmd, ...)} — POSIX only; {@code null} on Windows
 * @param ioctlsocket {@code int ioctlsocket(SOCKET, long, u_long*)} — Windows only; {@code null} on POSIX
 * @param wsaCleanup  {@code int WSACleanup(void)} — Windows only; {@code null} on POSIX.
 *                    Must be invoked to pair with {@code WSAStartup} before the owning arena is closed.
 * @since 0.5.0
 */
public record SyscallHandles(
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
     * Returns {@code true} if the Windows {@code WSACleanup} handle is available.
     * Must be invoked before the owning arena is closed to release Winsock resources.
     *
     * @return {@code true} when {@link #wsaCleanup()} is non-null
     */
    public boolean hasWsaCleanup() {
        return wsaCleanup != null;
    }
}
