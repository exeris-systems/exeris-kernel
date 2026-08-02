/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.syscall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test: the resolved Berkeley socket handles actually carry bytes over loopback.
 *
 * <h2>Why this test exists</h2>
 * <p>{@code CoreSyscallLoaderTest} and {@code SyscallHandlesTest} prove that every symbol
 * <em>resolves</em> and that no handle is null on its platform. Nothing invoked one. Until this test
 * the build could not tell a working socket seam from a set of handles that merely exists — a real
 * connection, a real payload, and a byte-exact comparison on the far side is the difference.
 *
 * <h2>What mutation showed it catches — and does not</h2>
 * <p>Measured, not assumed. Binding {@code recv} to the {@code send} symbol is caught, and caught
 * only by the content assertion: both counts still read 15, and the buffer came back all zeros. A
 * descriptor error that reaches the wrong function, or any regression that stops bytes moving, fails
 * here.
 *
 * <p>What it does <em>not</em> catch is worth stating so nobody trusts it further than it goes:
 * declaring {@code send}'s {@code size_t} as {@code JAVA_INT} instead of {@code JAVA_LONG} passes.
 * The x86-64 SysV ABI zero-extends the argument register, so a small length is indistinguishable
 * either way, and no payload this test could reasonably carry would separate them. Integer-width
 * entries in the C-type→{@code ValueLayout} table documented on {@link CoreSyscallLoader} therefore
 * remain reviewed-not-tested on this platform.
 *
 * <h2>Not tagged</h2>
 * <p>Deliberately carries no {@code @Tag("integration")}. The class name ends in {@code IT}, and
 * {@code exeris-kernel-core/pom.xml} binds Failsafe over {@code **&#47;*IT.java} at
 * {@code integration-test}/{@code verify}, so the standard build runs it. Adding the tag would drop it
 * from Surefire-only opt-out paths ({@code -DexcludedGroups=integration}) while Failsafe ran it
 * anyway — the exact mismatch removed from {@code OffHeapTlsEngineLoopbackIT} in v0.8 Sprint 6.
 *
 * <h2>Platform</h2>
 * <p>Linux and Windows. macOS is excluded on a concrete layout difference, not caution: BSD
 * {@code sockaddr_in} opens with a one-byte {@code sin_len} before {@code sin_family}, so the
 * 16-byte address this test builds would be read with the family in the wrong place. Writing a
 * second, unexecuted layout would be guessing; the exclusion is honest and reversible.
 *
 * @since 0.11.0
 */
@EnabledOnOs({OS.LINUX, OS.WINDOWS})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("IT: Berkeley socket handles — loopback round-trip over the resolved FFM seam")
class SyscallLoopbackRoundTripIT {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int BACKLOG = 1;

    /** {@code sizeof(struct sockaddr_in)} on Linux and Win32 alike. */
    private static final int SOCKADDR_IN_BYTES = 16;

    private static final byte[] PAYLOAD = "EXERIS-LOOPBACK".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("A byte written through send() arrives through recv()")
    class RoundTrip {

        @Test
        @DisplayName("connect → accept → send → recv returns the same bytes, and the same count")
        void loopbackRoundTrip() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                SyscallHandles handles = CoreSyscallLoader.load(arena);
                assertThat(handles.supportsPlainSocketIo())
                        .as("the seam under test must be fully resolved before anything is asserted "
                                + "about it")
                        .isTrue();

                long serverFd = -1;
                long clientFd = -1;
                long acceptedFd = -1;
                try {
                    serverFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(serverFd).as("socket() for the listener").isNotNegative();

                    MemorySegment bindAddress = sockaddrIn(arena, 0);
                    assertThat(call(handles.bind(), fd(handles.bind(), serverFd), bindAddress,
                            SOCKADDR_IN_BYTES))
                            .as("bind() to 127.0.0.1:0").isZero();
                    assertThat(call(handles.listen(), fd(handles.listen(), serverFd), BACKLOG))
                            .as("listen()").isZero();

                    int port = boundPort(arena, serverFd);
                    assertThat(port)
                            .as("the kernel must have assigned an ephemeral port; a zero here means "
                                    + "getsockname wrote nothing and every later assertion is vacuous")
                            .isPositive();

                    clientFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(clientFd).as("socket() for the client").isNotNegative();

                    MemorySegment target = sockaddrIn(arena, port);
                    assertThat(call(handles.connect(), fd(handles.connect(), clientFd), target,
                            SOCKADDR_IN_BYTES))
                            .as("connect() completes off the listen backlog without a concurrent "
                                    + "accept(), which is what keeps this test single-threaded")
                            .isZero();

                    acceptedFd = call(handles.accept(), fd(handles.accept(), serverFd),
                            MemorySegment.NULL, MemorySegment.NULL);
                    assertThat(acceptedFd).as("accept()").isNotNegative();

                    MemorySegment outbound = arena.allocate(PAYLOAD.length);
                    MemorySegment.copy(MemorySegment.ofArray(PAYLOAD), 0L, outbound, 0L,
                            PAYLOAD.length);

                    long sent = call(handles.send(), fd(handles.send(), clientFd), outbound,
                            length(handles.send(), PAYLOAD.length), 0);
                    assertThat(sent)
                            .as("send() must report the exact byte count — a size_t/ssize_t mapped to "
                                    + "the wrong width shows up here and nowhere earlier")
                            .isEqualTo(PAYLOAD.length);

                    MemorySegment inbound = arena.allocate(PAYLOAD.length);
                    long received = call(handles.recv(), fd(handles.recv(), acceptedFd), inbound,
                            length(handles.recv(), PAYLOAD.length), 0);
                    assertThat(received).as("recv() byte count").isEqualTo(PAYLOAD.length);

                    assertThat(inbound.toArray(JAVA_BYTE))
                            .as("the bytes themselves, not just the counts — a correct length over "
                                    + "wrong content is still a broken seam")
                            .containsExactly(PAYLOAD);
                } finally {
                    closeQuietly(handles, acceptedFd);
                    closeQuietly(handles, clientFd);
                    closeQuietly(handles, serverFd);
                    releaseWinsock(handles);
                }
            }
        }
    }

    @Nested
    @DisplayName("A refused connection is reported, not swallowed")
    class Refusal {

        /**
         * Asserts both outcomes through one handle, and the pairing is the point.
         *
         * <p>An earlier version asserted only the {@code -1}. Mutation testing found it vacuous: bind
         * {@code connect} to any symbol that fails on these arguments and the case still passed, because
         * "correctly refused" and "broken beyond use" both return {@code -1}. Only the successful
         * connection immediately before it makes the refusal mean anything.
         */
        @Test
        @DisplayName("the same handle returns 0 to a listener and -1 to a bound-but-unlistening port")
        void connectDistinguishesRefusalFromSuccess() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                SyscallHandles handles = CoreSyscallLoader.load(arena);

                long listenerFd = -1;
                long parkedFd = -1;
                long acceptedClientFd = -1;
                long refusedClientFd = -1;
                try {
                    listenerFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(call(handles.bind(), fd(handles.bind(), listenerFd),
                            sockaddrIn(arena, 0), SOCKADDR_IN_BYTES)).isZero();
                    assertThat(call(handles.listen(), fd(handles.listen(), listenerFd), BACKLOG))
                            .isZero();
                    int listeningPort = boundPort(arena, listenerFd);

                    // Hold a port without listening on it. Picking a port and closing it would race
                    // another process into the gap; owning it makes the refusal deterministic.
                    parkedFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(call(handles.bind(), fd(handles.bind(), parkedFd),
                            sockaddrIn(arena, 0), SOCKADDR_IN_BYTES)).isZero();
                    int parkedPort = boundPort(arena, parkedFd);
                    assertThat(parkedPort).isNotEqualTo(listeningPort);

                    acceptedClientFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(call(handles.connect(), fd(handles.connect(), acceptedClientFd),
                            sockaddrIn(arena, listeningPort), SOCKADDR_IN_BYTES))
                            .as("positive control — without it, the refusal below cannot tell a working "
                                    + "connect() from one that fails on everything")
                            .isZero();

                    refusedClientFd = call(handles.socket(), AF_INET, SOCK_STREAM, 0);
                    assertThat(call(handles.connect(), fd(handles.connect(), refusedClientFd),
                            sockaddrIn(arena, parkedPort), SOCKADDR_IN_BYTES))
                            .as("nothing is listening on a bound-but-unlistened port, so the kernel "
                                    + "refuses and the handle must surface that as -1")
                            .isEqualTo(-1L);
                } finally {
                    closeQuietly(handles, refusedClientFd);
                    closeQuietly(handles, acceptedClientFd);
                    closeQuietly(handles, parkedFd);
                    closeQuietly(handles, listenerFd);
                    releaseWinsock(handles);
                }
            }
        }
    }

    // =========================================================================
    // Scaffolding
    // =========================================================================

    /**
     * Invokes a resolved handle and normalises the result to {@code long}.
     *
     * <p>{@code invokeWithArguments} rather than {@code invokeExact} on purpose: POSIX types a
     * descriptor around {@code int} file descriptors while Winsock uses a 64-bit {@code SOCKET}, so an
     * exact call site would have to exist twice. Test scaffolding, never a hot path — the cost is a
     * boxed argument per syscall in a test that makes a handful.
     */
    private static long call(MethodHandle handle, Object... args) throws Throwable {
        return ((Number) handle.invokeWithArguments(args)).longValue();
    }

    /** Boxes a descriptor as the width this platform's resolved signature declares for it. */
    private static Object fd(MethodHandle handle, long descriptor) {
        return handle.type().parameterType(0) == long.class
                ? (Object) descriptor
                : (Object) (int) descriptor;
    }

    /** Same idea for the send/recv length: {@code size_t} on POSIX, {@code int} on Winsock. */
    private static Object length(MethodHandle handle, int bytes) {
        return handle.type().parameterType(2) == long.class
                ? (Object) (long) bytes
                : (Object) bytes;
    }

    /**
     * Reads back the port the kernel assigned to a socket bound to port 0.
     *
     * <p>{@code getsockname} is resolved here rather than added to {@link SyscallHandles}: the runtime
     * never needs it, and widening a production seam to serve a test is how seams grow.
     */
    private static int boundPort(Arena arena, long socketFd) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = IS_WINDOWS
                ? SymbolLookup.libraryLookup("Ws2_32.dll", arena)
                : linker.defaultLookup();
        MethodHandle getsockname = linker.downcallHandle(
                lookup.find("getsockname").orElseThrow(
                        () -> new IllegalStateException("getsockname not found")),
                FunctionDescriptor.of(JAVA_INT, IS_WINDOWS ? JAVA_LONG : JAVA_INT, ADDRESS, ADDRESS));

        MemorySegment address = arena.allocate(SOCKADDR_IN_BYTES, 8);
        MemorySegment addressLength = arena.allocate(JAVA_INT);
        addressLength.set(JAVA_INT, 0L, SOCKADDR_IN_BYTES);

        long result = call(getsockname, fd(getsockname, socketFd), address, addressLength);
        assertThat(result).as("getsockname()").isZero();

        // sin_port is network byte order regardless of host endianness.
        int high = address.get(JAVA_BYTE, 2L) & 0xFF;
        int low = address.get(JAVA_BYTE, 3L) & 0xFF;
        return (high << 8) | low;
    }

    /**
     * Builds {@code sockaddr_in} for {@code 127.0.0.1:port}.
     *
     * <p>Port and address are written byte-wise because both are network byte order while
     * {@code sin_family} is host order — mixing the two through a single multi-byte layout is the
     * classic way to bind to a port nobody asked for.
     */
    private static MemorySegment sockaddrIn(Arena arena, int port) {
        MemorySegment address = arena.allocate(SOCKADDR_IN_BYTES, 8);
        address.set(JAVA_SHORT, 0L, (short) AF_INET);
        address.set(JAVA_BYTE, 2L, (byte) (port >>> 8));
        address.set(JAVA_BYTE, 3L, (byte) port);
        address.set(JAVA_BYTE, 4L, (byte) 127);
        address.set(JAVA_BYTE, 5L, (byte) 0);
        address.set(JAVA_BYTE, 6L, (byte) 0);
        address.set(JAVA_BYTE, 7L, (byte) 1);
        return address;
    }

    private static void closeQuietly(SyscallHandles handles, long descriptor) throws Throwable {
        if (descriptor >= 0) {
            call(handles.close(), fd(handles.close(), descriptor));
        }
    }

    /**
     * Pairs the {@code WSAStartup} that {@link CoreSyscallLoader#load} performs on Windows.
     *
     * <p>{@link SyscallHandles#wsaCleanup()} documents that it must run before the owning arena closes.
     * These tests load into a fresh {@link Arena#ofConfined()} per method, so without this each method
     * would close its arena on an unbalanced Winsock refcount — the sibling
     * {@code CoreSyscallLoaderTest} does not have the problem because it loads once into
     * {@link Arena#global()}, which never closes.
     *
     * <p>No-op on POSIX, where the handle is null by the loader's own convention. Unverified in this
     * repository's CI, which has no Windows runner — it satisfies the documented contract rather than a
     * green run.
     */
    private static void releaseWinsock(SyscallHandles handles) throws Throwable {
        if (handles.hasWsaCleanup()) {
            call(handles.wsaCleanup());
        }
    }
}
