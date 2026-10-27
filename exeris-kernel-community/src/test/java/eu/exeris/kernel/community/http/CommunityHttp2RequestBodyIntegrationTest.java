/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request body larger than the default 65 535-byte HTTP/2 window reaches a respond-once handler whole,
 * on a real server, over TLS with ALPN {@code h2} and over cleartext prior knowledge. The client sends
 * only what the server's windows allow ({@link Http2UploadTestClient}), so a server that does not return
 * credit leaves the upload stalled and the case fails at its deadline.
 *
 * <p>The server is the production one — {@code CommunityHttpProvider#createServerEngine}. Fails rather
 * than skips when OpenSSL cannot be loaded, because a skipped case reports success.
 */
@ExtendWith(HandlerFailureLogGuard.class)
@DisplayName("Community: request bodies over HTTP/2 on a real server")
class CommunityHttp2RequestBodyIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int BODY_BYTES = 1 << 20;

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestCertificate certificate;
    private static SSLContext clientTrust;

    @BeforeAll
    static void loadOpenSslAndMaterial() throws Exception {
        crypto = new CommunityKernelCryptoProvider();
        certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        clientTrust = Http2TestClient.trusting(certificate.certificate());
    }

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("1 MiB over TLS with ALPN h2 reaches the handler whole")
    void aMebibyteOverTlsReachesTheHandlerWhole() {
        byte[] body = body();
        Started server = startServer(true);
        try (Http2UploadTestClient client = Http2UploadTestClient.connectTls(
                server.port(), clientTrust, "/echo")) {
            client.upload(body);

            assertThat(client.awaitResponseBody()).isEqualTo(summary(body));
            assertThat(client.status()).isEqualTo("200");
            assertThat(client.goAwayReceived()).isFalse();
        } finally {
            server.engine().close();
        }
    }

    @Test
    @DisplayName("1 MiB over cleartext prior knowledge reaches the handler whole")
    void aMebibyteOverPriorKnowledgeReachesTheHandlerWhole() {
        byte[] body = body();
        Started server = startServer(false);
        try (Http2UploadTestClient client = Http2UploadTestClient.connectPlain(server.port(), "/echo")) {
            client.upload(body);

            assertThat(client.awaitResponseBody()).isEqualTo(summary(body));
            assertThat(client.status()).isEqualTo("200");
            assertThat(client.goAwayReceived()).isFalse();
        } finally {
            server.engine().close();
        }
    }

    @Test
    @DisplayName("1 MiB after an h2c upgrade reaches the handler whole")
    void aMebibyteAfterAnH2cUpgradeReachesTheHandlerWhole() {
        byte[] body = body();
        Started server = startServer(false);
        try (Http2UploadTestClient client = Http2UploadTestClient.connectUpgrade(server.port(), "/echo")) {
            client.upload(body);

            assertThat(client.awaitResponseBody()).isEqualTo(summary(body));
            assertThat(client.status()).isEqualTo("200");
            assertThat(client.goAwayReceived()).isFalse();
        } finally {
            server.engine().close();
        }
    }

    private static byte[] body() {
        byte[] bytes = new byte[BODY_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return bytes;
    }

    private static String summary(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return body.length + ":" + crc.getValue();
    }

    private static Started startServer(boolean tls) {
        int port = freePort();
        HttpConfig config = new HttpConfig(
                HttpMode.SERVER,
                HOST,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
        HttpServerEngine[] holder = new HttpServerEngine[1];
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR);
        if (tls) {
            MapConfigProvider kernelConfig = new MapConfigProvider(
                    Map.of("transport.certPath", certificate.certificate().toString(),
                            "transport.keyPath", certificate.privateKey().toString()),
                    Map.of());
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .where(KernelProviders.CURRENT_CONFIG, kernelConfig);
        }
        scope.run(() -> holder[0] = new CommunityHttpProvider().createServerEngine(config));
        holder[0].setHandler(new Echo());
        holder[0].start();
        return new Started(holder[0], port);
    }

    private record Started(HttpServerEngine engine, int port) {
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    /** Answers {@code POST /echo} with the length and CRC-32 of the request body it received. */
    private static final class Echo implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) {
            LoanedBuffer received = exchange.request().body();
            byte[] bytes = received == null
                    ? new byte[0]
                    : received.segment().asSlice(0, received.size()).toArray(ValueLayout.JAVA_BYTE);
            byte[] text = summary(bytes).getBytes(StandardCharsets.US_ASCII);
            LoanedBuffer response = ALLOCATOR.allocateNetwork(text.length);
            MemorySegment.copy(MemorySegment.ofArray(text), 0, response.segment(), 0, text.length);
            response.setSize(text.length);
            exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), response));
        }
    }
}
