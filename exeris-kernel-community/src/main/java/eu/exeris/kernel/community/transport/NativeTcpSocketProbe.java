/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Locale;

/**
 * Package-private static helpers for socket bootstrap validation probes.
 *
 * <p>Holds the native socket FFM helpers ({@code socket()} / {@code bind()} /
 * {@code connect()} / {@code listen()} / {@code close()}), the IPv4 sockaddr
 * serializer, and the server/client validation probes that {@link NativeTcpSocketBackend}
 * invokes once on first server-start / first client-connect.
 *
 * <p>All entry points are static — no instance state lives in this class.
 * Validation latches are held by {@link NativeTcpSocketBackend}.
 */
@SuppressWarnings({
    "PMD.AvoidCatchingGenericException", // FFM downcalls throw Throwable; probes must catch defensively.
    "PMD.LawOfDemeter"                   // probe uses NIO ServerSocketChannel.getLocalAddress for port discovery.
})
final class NativeTcpSocketProbe {

    /* default */ static final String LOOPBACK_HOST = InetAddress.getLoopbackAddress().getHostAddress();
    /* default */ static final boolean IS_WINDOWS_RUNTIME =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final System.Logger LOG = System.getLogger(NativeTcpSocketProbe.class.getName());
    private static final byte LOOPBACK_FIRST_OCTET = 127;
    private static final byte LOOPBACK_SECOND_OCTET = 0;
    private static final byte LOOPBACK_THIRD_OCTET = 0;
    private static final byte LOOPBACK_FOURTH_OCTET = 1;
    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int DEFAULT_IP_PROTOCOL = 0;
    private static final int SOCKADDR_IN_SIZE = 16;
    private static final boolean SOCKADDR_INCLUDES_LENGTH = usesBsdSockaddrLayout();

    private NativeTcpSocketProbe() {
        // package-private static utility — never instantiated.
    }

    /* default */ static boolean validateServerSocketBootstrap(MemoryAllocator allocator, SyscallHandles handles) {
        int socketFd = -1;
        try (LoanedBuffer scratch = allocator.allocateInfrastructure(SOCKADDR_IN_SIZE)) {
            socketFd = openPosixStreamSocket(handles);
            MemorySegment sockaddr = scratch.segment().asSlice(0, SOCKADDR_IN_SIZE);
            writeIpv4Sockaddr(sockaddr,
                    0,
                    LOOPBACK_FIRST_OCTET,
                    LOOPBACK_SECOND_OCTET,
                    LOOPBACK_THIRD_OCTET,
                    LOOPBACK_FOURTH_OCTET);
            return bindSocket(handles, socketFd, sockaddr) == 0
                    && listenSocket(handles, socketFd) == 0;
        } catch (RuntimeException _) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Core socket server bootstrap probe failed; compatibility fallback remains active.");
            return false;
        } finally {
            closePosixSocketQuietly(handles, socketFd);
        }
    }

    /* default */ static boolean validateClientSocketConnect(MemoryAllocator allocator,
                                                             SyscallHandles handles,
                                                             String host,
                                                             int port) {
        int socketFd = -1;
        try (ServerSocketChannel probeListener = ServerSocketChannel.open();
             LoanedBuffer scratch = allocator.allocateInfrastructure(SOCKADDR_IN_SIZE)) {
            probeListener.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            int probePort = ((InetSocketAddress) probeListener.getLocalAddress()).getPort();

            socketFd = openPosixStreamSocket(handles);
            MemorySegment sockaddr = scratch.segment().asSlice(0, SOCKADDR_IN_SIZE);
            writeIpv4Sockaddr(sockaddr,
                    probePort,
                    LOOPBACK_FIRST_OCTET,
                    LOOPBACK_SECOND_OCTET,
                    LOOPBACK_THIRD_OCTET,
                    LOOPBACK_FOURTH_OCTET);
            if (connectSocket(handles, socketFd, sockaddr) != 0) {
                return false;
            }

            try (SocketChannel accepted = probeListener.accept()) {
                return accepted != null;
            }
        } catch (IOException | RuntimeException _) {
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "Core socket client probe failed for " + host + ':' + port
                            + "; compatibility fallback remains active.");
            return false;
        } finally {
            closePosixSocketQuietly(handles, socketFd);
        }
    }

    /* default */ static void bestEffortWsaCleanup(SyscallHandles handles) {
        if (handles == null || !handles.hasWsaCleanup()) {
            return;
        }
        try {
            handles.wsaCleanup().invokeExact();
        } catch (Throwable _) {
            // best effort
        }
    }

    private static void writeIpv4Sockaddr(MemorySegment target,
                                          int port,
                                          byte firstOctet,
                                          byte secondOctet,
                                          byte thirdOctet,
                                          byte fourthOctet) {
        target.fill((byte) 0);
        if (SOCKADDR_INCLUDES_LENGTH) {
            target.set(ValueLayout.JAVA_BYTE, 0, (byte) SOCKADDR_IN_SIZE);
            target.set(ValueLayout.JAVA_BYTE, 1, (byte) AF_INET);
        } else {
            target.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
        }
        target.set(ValueLayout.JAVA_BYTE, 2, (byte) ((port >>> 8) & 0xFF));
        target.set(ValueLayout.JAVA_BYTE, 3, (byte) (port & 0xFF));
        target.set(ValueLayout.JAVA_BYTE, 4, firstOctet);
        target.set(ValueLayout.JAVA_BYTE, 5, secondOctet);
        target.set(ValueLayout.JAVA_BYTE, 6, thirdOctet);
        target.set(ValueLayout.JAVA_BYTE, 7, fourthOctet);
    }

    private static boolean usesBsdSockaddrLayout() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac")
                || osName.contains("darwin")
                || osName.contains("bsd");
    }

    private static int openPosixStreamSocket(SyscallHandles handles) {
        try {
            return (int) handles.socket().invokeExact(AF_INET, SOCK_STREAM, DEFAULT_IP_PROTOCOL);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core socket() probe failed", ex);
        }
    }

    private static int bindSocket(SyscallHandles handles, int socketFd, MemorySegment sockaddr) {
        try {
            return (int) handles.bind().invokeExact(socketFd, sockaddr, SOCKADDR_IN_SIZE);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core bind() probe failed", ex);
        }
    }

    private static int connectSocket(SyscallHandles handles, int socketFd, MemorySegment sockaddr) {
        try {
            return (int) handles.connect().invokeExact(socketFd, sockaddr, SOCKADDR_IN_SIZE);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core connect() probe failed", ex);
        }
    }

    private static int listenSocket(SyscallHandles handles, int socketFd) {
        try {
            return (int) handles.listen().invokeExact(socketFd, 1);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core listen() probe failed", ex);
        }
    }

    private static void closePosixSocketQuietly(SyscallHandles handles, int socketFd) {
        if (socketFd < 0) {
            return;
        }
        try {
            int _ = (int) handles.close().invokeExact(socketFd);
        } catch (Throwable _) {
            // best effort cleanup on the validation probe path
        }
    }
}
