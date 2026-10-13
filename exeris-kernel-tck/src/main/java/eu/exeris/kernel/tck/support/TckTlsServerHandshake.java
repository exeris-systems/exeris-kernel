/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.support;

import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * One loopback TLS handshake between a {@link TlsEngine} acting as the server and a JDK JSSE
 * client restricted to a single protocol version, judged on the wire.
 *
 * <p>The engine is seen only through the SPI; how it is bound to the accepted connection is the
 * caller's {@code binder}. The JSSE client is the counterpart whose enabled protocol is set with
 * {@code setEnabledProtocols}, so the outcome does not depend on how the engine is implemented.
 */
public final class TckTlsServerHandshake {

    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final int MAX_STEPS = 64;

    private TckTlsServerHandshake() {
        // Static entry point only.
    }

    /**
     * What one handshake showed.
     *
     * @param engineCompleted whether the engine reported the handshake complete
     * @param peerProtocol    the protocol the JSSE client negotiated, or {@code null} if it
     *                        negotiated none
     * @param peerFailure     what the JSSE client saw fail, or {@code null}
     * @param timedOut        whether either side was still running at the deadline
     * @param fixtureFailure  what escaped either side's body, or {@code null}
     */
    public record Outcome(boolean engineCompleted, String peerProtocol, Throwable peerFailure,
                          boolean timedOut, Throwable fixtureFailure) {
    }

    /**
     * Runs the handshake.
     *
     * @param engine       a server-side engine holding {@code material}'s certificate
     * @param material     the certificate the JSSE client trusts
     * @param peerProtocol the only protocol version the JSSE client enables, e.g. {@code "TLSv1.2"}
     * @param allocator    source of the handshake output buffer
     * @param binder       binds {@code engine} to the accepted, blocking channel
     * @return what happened
     * @throws Exception if the fixture itself cannot be set up
     */
    public static Outcome run(TlsEngine engine, TckTlsMaterial material, String peerProtocol,
                              MemoryAllocator allocator, BiConsumer<TlsEngine, SocketChannel> binder)
            throws Exception {
        SSLContext trusting = trustingContext(material);
        try (ServerSocketChannel listen = ServerSocketChannel.open()) {
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();
            AtomicReference<String> protocol = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();

            BlockingPeerPair.Outcome run = BlockingPeerPair.drive(DEADLINE,
                    () -> {
                        try (SocketChannel accepted = listen.accept();
                             LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
                            binder.accept(engine, accepted);
                            completed.set(drive(engine, outbound));
                        }
                    },
                    () -> {
                        try (SSLSocket socket = (SSLSocket) trusting.getSocketFactory().createSocket()) {
                            socket.setEnabledProtocols(new String[] {peerProtocol});
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                            socket.setSoTimeout(10_000);
                            socket.startHandshake();
                            protocol.set(socket.getSession().getProtocol());
                        } catch (IOException refused) {
                            failure.set(refused);
                        }
                    });
            Throwable fixtureFailure = run.serverFailure() != null ? run.serverFailure() : run.clientFailure();
            return new Outcome(completed.get(), protocol.get(), failure.get(), run.timedOut(), fixtureFailure);
        }
    }

    /**
     * Deletes {@code directory} and everything under it.
     *
     * @param directory the directory to remove
     * @throws IOException if a file cannot be deleted
     */
    public static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Steps the handshake until it completes or the engine refuses it. */
    private static boolean drive(TlsEngine engine, LoanedBuffer outbound) {
        try {
            for (int i = 0; i < MAX_STEPS && !engine.isHandshakeComplete(); i++) {
                engine.beginHandshake(outbound);
            }
        } catch (RuntimeException refused) {
            return false;
        }
        return engine.isHandshakeComplete();
    }

    private static SSLContext trustingContext(TckTlsMaterial material) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", material.x509());
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
