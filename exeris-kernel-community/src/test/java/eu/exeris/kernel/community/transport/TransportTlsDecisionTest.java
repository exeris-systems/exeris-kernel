/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An outbound call speaks TLS because it is configured to, not because <em>another subsystem has
 * started</em>.
 *
 * <p>The server's TLS is a property of what it was given — certificate and key — and the client's
 * is too, not a property of its surroundings. Were {@code resolveCryptoConfig} to hand every
 * {@code CLIENT}-mode transport a TLS config for the carrier to arm whenever a crypto provider is
 * bound, a kernel that boots crypto to serve HTTPS could not make a plaintext outbound call at
 * all, and nothing would report that — the request would simply never complete.
 *
 * <p>Both directions are asserted here because the knob proves nothing alone: a client that never
 * armed TLS would pass the opt-out case, and one that always armed it would pass the default case.
 */
@DisplayName("Community: outbound TLS is a decision, not a consequence of crypto booting")
class TransportTlsDecisionTest {

    private static final String TLS_PROPERTY = "exeris.transport.tls";
    private static final String DECLINE_EVENT = "eu.exeris.kernel.transport.TransportTlsDeclined";

    @Test
    @DisplayName("the opt-out reaches a plaintext peer; without it the client still arms TLS")
    void optOutIsHonouredAndTheDefaultStillArmsTls() throws Exception {
        KernelCryptoProvider crypto;
        try {
            crypto = new CommunityKernelCryptoProvider();
        } catch (CryptoBootstrapException _) {
            assumeTrue(false, "OpenSSL not available — this test is about what a bound crypto provider implies");
            return;
        }

        MemoryAllocator allocator =
                new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        try {
            // The opt-out: a crypto-booted kernel reaches a plaintext peer.
            Outcome declined = againstPlaintextServer(crypto, allocator, "false");
            assertThat(declined.status())
                    .as("opt-out must produce a plaintext client, which the plaintext peer answers; failure: %s",
                            declined.failure())
                    .isEqualTo(HttpStatus.OK.code());

            // The default, deliberately unchanged: a bound crypto provider still arms TLS, so the
            // plaintext peer yields nothing. Asserting the failure is what keeps the case above from
            // passing against a client that simply never arms TLS — and it pins the default the TLS
            // end-to-end tests depend on, since their client carries no certificate of its own.
            Outcome armed = againstPlaintextServer(crypto, allocator, null);
            assertThat(armed.status())
                    .as("default must still arm TLS, so a plaintext peer yields no status")
                    .isEqualTo(-1);
            // The handshake is what fails, which only an armed client runs: the plaintext peer either
            // answers bytes that are not TLS (EX-NET-2001) or waits for a request line the ClientHello
            // never completes, and the handshake times out (EX-NET-4003). A client that failed to
            // construct (EX-NET-4004) would also yield -1, and is not an armed client.
            assertThat(armed.failure())
                    .as("and the refusal is the handshake's, not a construction failure passing as armed")
                    .isInstanceOf(ExerisKernelException.class);
            assertThat(((ExerisKernelException) armed.failure()).errorCode())
                    .isIn(KernelErrorCodes.EX_NET_2001, KernelErrorCodes.EX_NET_4003);
        } finally {
            allocator.close();
        }
    }

    /**
     * What one exchange produced.
     *
     * @param status  the response status, or {@code -1} when the exchange did not complete
     * @param failure what the exchange threw, or {@code null}
     */
    private record Outcome(int status, Throwable failure) {
    }

    private Outcome againstPlaintextServer(KernelCryptoProvider crypto,
                                           MemoryAllocator allocator,
                                           String optOut) throws Exception {
        String previous = System.getProperty(TLS_PROPERTY);
        if (optOut == null) {
            System.clearProperty(TLS_PROPERTY);
        } else {
            System.setProperty(TLS_PROPERTY, optOut);
        }
        int port = freePort();
        AtomicReference<Integer> status = new AtomicReference<>(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .run(() -> exchange(port, status, failure));
        } finally {
            if (previous == null) {
                System.clearProperty(TLS_PROPERTY);
            } else {
                System.setProperty(TLS_PROPERTY, previous);
            }
        }
        return new Outcome(status.get(), failure.get());
    }

    private void exchange(int port, AtomicReference<Integer> status, AtomicReference<Throwable> failure) {
        CommunityHttpProvider provider = new CommunityHttpProvider();
        // No certificate anywhere, so the server is plaintext whatever the client decides.
        try (HttpServerEngine server = provider.createServerEngine(config(HttpMode.SERVER, port, null));
             HttpClientEngine client = provider.createClientEngine(config(HttpMode.CLIENT, port, "127.0.0.1:" + port))) {
            server.setHandler(ex -> ex.respond(HttpResponse.noBody(HttpStatus.OK, ex.request().version())));
            server.start();
            client.start();
            HttpResponse response = client.send(HttpRequest.noBody(
                    HttpMethod.GET, "/", HttpVersion.HTTP_1_1, List.of()));
            status.set(response.status().code());
            if (response.body() != null) {
                response.body().close();
            }
        } catch (RuntimeException refused) {
            // A TLS client against a plaintext peer does not complete; -1 records that.
            status.set(-1);
            failure.set(refused);
        }
    }

    private static HttpConfig config(HttpMode mode, int port, String authority) {
        return new HttpConfig(mode, "127.0.0.1", port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, false, HttpVersion.HTTP_1_1,
                authority, HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE, HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    /**
     * The listener half of the same knob, which the client cases never reach: their server carries
     * no material, so {@code resolveListenerCryptoConfig} returns before the opt-out is read.
     *
     * <p>This is the branch that can downgrade something real — a listener holding a valid
     * certificate and binding plaintext anyway — and the one outcome of the decision that is
     * invisible from outside the process. It is asserted through the signal added for exactly that
     * reason, in both directions: a listener that never emitted would satisfy the silent case on its
     * own, and one that always emitted would satisfy the declined case on its own.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a listener with real material announces the decline, and stays silent without it")
    void listenerAnnouncesTheDeclineAndIsSilentOtherwise(@TempDir Path materialDir) throws Exception {
        try (CommunityKernelCryptoProvider probe = new CommunityKernelCryptoProvider()) {
            assertThat(probe).isNotNull();
        } catch (CryptoBootstrapException _) {
            assumeTrue(false, "OpenSSL not available — this test is about a listener that holds material");
            return;
        }
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(materialDir);

        assertThat(declineEventsWhile(certificate, "false"))
                .as("a listener that holds material and serves plaintext must leave a trail")
                .isPositive();
        assertThat(declineEventsWhile(certificate, null))
                .as("and must say nothing when it does arm TLS, or the signal means nothing")
                .isZero();
    }

    /** Builds a listener transport with real material and counts the decline events it emits. */
    private long declineEventsWhile(TlsTestCertificate certificate, String optOut) throws Exception {
        String previous = System.getProperty(TLS_PROPERTY);
        AtomicLong seen = new AtomicLong();
        CountDownLatch arrived = new CountDownLatch(1);
        MemoryAllocator allocator =
                new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(DECLINE_EVENT);
            stream.onEvent(DECLINE_EVENT, event -> {
                seen.incrementAndGet();
                arrived.countDown();
            });
            stream.startAsync();
            if (optOut == null) {
                System.clearProperty(TLS_PROPERTY);
            } else {
                System.setProperty(TLS_PROPERTY, optOut);
            }
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .run(() -> buildListener(certificate));
            // Bounded either way: the declined run has an event to wait for, the armed run has
            // nothing and the wait is what gives a stray event time to show up before we call it zero.
            arrived.await(5, TimeUnit.SECONDS);
        } finally {
            if (previous == null) {
                System.clearProperty(TLS_PROPERTY);
            } else {
                System.setProperty(TLS_PROPERTY, previous);
            }
            allocator.close();
        }
        return seen.get();
    }

    private void buildListener(TlsTestCertificate certificate) {
        try {
            new NativeTcpTransportProvider().createEngine(new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", freePort(), 1,
                    certificate.certPath(), certificate.keyPath(), 1024, 30_000)).close();
        } catch (IOException cause) {
            throw new IllegalStateException("no free port", cause);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
