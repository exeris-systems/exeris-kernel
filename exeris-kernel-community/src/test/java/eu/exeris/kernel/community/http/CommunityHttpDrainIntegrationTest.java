/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shutdown must wait for requests, not for connections (issue #282).
 *
 * <p>The distinction only exists above the transport: a raw stream handler that parks is
 * indistinguishable from one that is working, so the transport correctly treats it as busy. Only the
 * HTTP codec knows it is between requests. That is why this case lives here and not in
 * {@code AbstractTransportEngineTck} — the first version of this test asserted an HTTP property
 * through a raw handler and failed for the right reason.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community HTTP: graceful drain and idle keep-alive connections")
class CommunityHttpDrainIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    /** Well under the drain's 60 s deadline, well over an unblocked shutdown. */
    private static final long ACCEPTABLE_SHUTDOWN_SECONDS = 15L;

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("an idle keep-alive connection does not hold stop() to the drain deadline")
    void idleKeepAliveConnectionDoesNotHoldShutdown() throws Exception {
        int port = nextFreePort();

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            HttpProvider provider = new CommunityHttpProvider();
            HttpServerEngine server = provider.createServerEngine(serverConfig(port));
            server.setHandler(exchange ->
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version())));
            server.start();

            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10L));
                exchangeOneKeepAliveRequest(client);

                // The client deliberately keeps its socket open. This is what every connection-pooling
                // HTTP client, load balancer and service mesh does between requests — and before the
                // fix it made shutdown wait out the full 60 s drain deadline, so a container runtime
                // SIGKILLed the process mid-shutdown on every rollout.
                long startNanos = System.nanoTime();
                server.close();
                long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);

                assertThat(elapsedSeconds)
                        .as("nothing is in flight — the response was fully received before shutdown "
                                + "began — so the drain has nothing to wait for. An idle keep-alive "
                                + "connection never closes on its own; that is what keep-alive means")
                        .isLessThan(ACCEPTABLE_SHUTDOWN_SECONDS);
            } catch (IOException e) {
                throw new IllegalStateException("keep-alive exchange failed", e);
            }
        });
    }

    @Test
    @DisplayName("a response issued while draining tells the peer to close")
    void drainingResponseAsksPeerToClose() throws Exception {
        int port = nextFreePort();

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            CountDownLatch handlerEntered = new CountDownLatch(1);
            AtomicBoolean secondRequest = new AtomicBoolean(false);

            HttpProvider provider = new CommunityHttpProvider();
            HttpServerEngine server = provider.createServerEngine(serverConfig(port));
            server.setHandler(exchange -> {
                if (secondRequest.get()) {
                    // Hold this request IN FLIGHT so the drain has something to wait for. Ordering
                    // matters and the first version of this test had it backwards: it started the
                    // shutdown while the connection was idle, which — by the very fix under test —
                    // completes the drain at once and tears the connection down before a second
                    // request can arrive. A response can only be encoded during a drain if a request
                    // was already in flight when the drain began.
                    handlerEntered.countDown();
                    // Release itself when the drain is actually marked. The engine's isRunning()
                    // flips false in stop()'s entry guard, BEFORE phase 2 marks draining, so it is
                    // the wrong signal — releasing on it encodes the response too early and the test
                    // sees keep-alive. Only the coordinator knows, and only this thread can read it.
                    awaitDrainingInScope();
                }
                exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
            });
            server.start();

            Thread stopper = null;
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(30L));

                assertThat(exchangeOneKeepAliveRequest(client))
                        .as("the control: before draining, a keep-alive response must not carry "
                                + "Connection: close, or the assertion below proves nothing")
                        .doesNotContain("Connection: close");

                secondRequest.set(true);
                sendKeepAliveRequest(client);
                assertThat(awaitQuietly(handlerEntered))
                        .as("the second request must be in flight before shutdown starts").isTrue();

                stopper = Thread.ofPlatform().start(server::close);

                assertThat(readResponseHead(client))
                        .as("a peer holding a pooled connection has no other way to learn it should "
                                + "release it; without this header it keeps the connection and the "
                                + "next shutdown waits on it again")
                        .contains("Connection: close");
            } catch (IOException e) {
                throw new IllegalStateException("keep-alive exchange failed", e);
            } finally {
                if (stopper != null) {
                    joinQuietly(stopper);
                }
                server.close();
            }
        });
    }

    /** Spins on the stream's own scope until the drain is marked. Runs on the handler thread. */
    private static void awaitDrainingInScope() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
        while (System.nanoTime() < deadline) {
            if (TransportScopes.DRAIN_COORDINATOR.isBound()
                    && TransportScopes.DRAIN_COORDINATOR.get().isDraining()) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(30L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(30L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String exchangeOneKeepAliveRequest(Socket client) throws IOException {
        sendKeepAliveRequest(client);
        String head = readResponseHead(client);
        assertThat(head)
                .as("the exchange must complete before shutdown, so nothing is genuinely in flight")
                .startsWith("HTTP/1.1 200");
        return head;
    }

    private static void sendKeepAliveRequest(Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readResponseHead(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        StringBuilder response = new StringBuilder(128);
        int b;
        while ((b = in.read()) != -1) {
            response.append((char) b);
            if (response.length() >= 4 && response.lastIndexOf("\r\n\r\n") == response.length() - 4) {
                break;
            }
        }
        return response.toString();
    }

    private static HttpConfig serverConfig(int port) {
        return new HttpConfig(
                HttpMode.SERVER, "127.0.0.1", port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, false, HttpVersion.HTTP_1_1);
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
