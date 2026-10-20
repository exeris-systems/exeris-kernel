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
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpServerPeerAddressTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Binds {@link AbstractHttpServerPeerAddressTck} to the Community HTTP server.
 *
 * <p>The plaintext server is built with no crypto provider bound, so its listener carries no TLS. The
 * TLS server is built the way a kernel builds one: with a {@link CommunityKernelCryptoProvider} bound,
 * and with the certificate and key paths in the bound kernel configuration, which the transport reads
 * when the engine is created. Both are created inside the scope that binds the memory allocator, as a
 * server engine requires. Fails rather than skips when OpenSSL cannot be loaded.
 */
@DisplayName("Community: HTTP server peer address TCK")
class CommunityHttpServerPeerAddressTckTest extends AbstractHttpServerPeerAddressTck {

    private static final String HOST = "127.0.0.1";

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
        clientTrust = trustingContext(certificate.certificate());
    }

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected StartedServer startServer(HttpHandler handler, Transport transport) {
        int port = nextFreePort();
        HttpConfig config = serverConfig(port);
        HttpServerEngine engine = transport == Transport.TLS
                ? createTlsEngine(config)
                : createPlaintextEngine(config);
        engine.setHandler(handler);
        engine.start();
        return new StartedServer(engine, HOST, port, transport == Transport.TLS ? clientTrust : null);
    }

    private static HttpConfig serverConfig(int port) {
        return new HttpConfig(
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
    }

    private static HttpServerEngine createPlaintextEngine(HttpConfig config) {
        return createEngine(config, null);
    }

    private static HttpServerEngine createTlsEngine(HttpConfig config) {
        MapConfigProvider kernelConfig = new MapConfigProvider(
                Map.of("transport.certPath", certificate.certificate().toString(),
                        "transport.keyPath", certificate.privateKey().toString()),
                Map.of());
        return createEngine(config, kernelConfig);
    }

    private static HttpServerEngine createEngine(HttpConfig config, MapConfigProvider kernelConfig) {
        HttpServerEngine[] holder = new HttpServerEngine[1];
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR);
        if (kernelConfig != null) {
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .where(KernelProviders.CURRENT_CONFIG, kernelConfig);
        }
        scope.run(() -> holder[0] = new CommunityHttpProvider().createServerEngine(config));
        return holder[0];
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    private static SSLContext trustingContext(Path certificatePath) throws Exception {
        X509Certificate server;
        try (InputStream in = Files.newInputStream(certificatePath)) {
            server = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", server);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
