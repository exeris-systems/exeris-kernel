/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTPS listener's ALPN answer follows {@code http.maxVersion}: a listener capped at HTTP/1.1
 * never selects {@code h2}, even when a client offers it first.
 */
@Tag("integration")
@Timeout(60)
@DisplayName("IT: HTTPS listener ALPN follows http.maxVersion")
class CommunityHttpAlpnMaxVersionIntegrationTest {

    private static final String[] OFFER_H2_THEN_HTTP_1_1 = {"h2", "http/1.1"};

    @TempDir
    Path tlsMaterialDir;

    @Test
    @DisplayName("a listener capped at HTTP/1.1 selects http/1.1 for a client that offers h2 first, and serves /health")
    void cappedListenerSelectsHttp11() throws Exception {
        Negotiation negotiation = bootAndNegotiate("HTTP_1_1", OFFER_H2_THEN_HTTP_1_1);

        assertThat(negotiation.protocol()).isEqualTo("http/1.1");
        assertThat(negotiation.statusLine()).isEqualTo("HTTP/1.1 200 OK");
    }

    @Test
    @DisplayName("a listener capped at HTTP/2 selects h2 for the same offer")
    void http2ListenerSelectsH2() throws Exception {
        Negotiation negotiation = bootAndNegotiate("HTTP_2", OFFER_H2_THEN_HTTP_1_1);

        assertThat(negotiation.protocol()).isEqualTo("h2");
    }

    @Test
    @DisplayName("a listener with no maxVersion selects h2 for the same offer")
    void uncappedListenerSelectsH2() throws Exception {
        Negotiation negotiation = bootAndNegotiate(null, OFFER_H2_THEN_HTTP_1_1);

        assertThat(negotiation.protocol()).isEqualTo("h2");
    }

    @Test
    @DisplayName("a listener capped at HTTP/1.1 that is offered only h2 negotiates no protocol")
    void cappedListenerRefusesH2OnlyOffer() throws Exception {
        Negotiation negotiation = bootAndNegotiate("HTTP_1_1", "h2");

        assertThat(negotiation.protocol()).isEmpty();
    }

    /** The protocol the client's handshake selected, and the status line of a {@code /health} reply if one was read. */
    private record Negotiation(String protocol, String statusLine) {
    }

    private Negotiation bootAndNegotiate(String maxVersion, String... offered) throws Exception {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        int port = nextFreePort();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("exeris.http.mode", "SERVER");
        settings.put("exeris.http.bindHost", "127.0.0.1");
        settings.put("exeris.http.port", Integer.toString(port));
        settings.put("exeris.transport.certPath", certificate.certificate().toString());
        settings.put("exeris.transport.keyPath", certificate.privateKey().toString());
        if (maxVersion != null) {
            settings.put("exeris.http.maxVersion", maxVersion);
        }
        Map<String, String> previous = new HashMap<>();
        settings.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });

        try {
            Negotiation[] result = new Negotiation[1];
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("crypto", "http"))
                    .build()
                    .boot(() -> {
                        try {
                            result[0] = negotiate(port, certificate.certificate(), offered);
                        } catch (IOException e) {
                            throw new IllegalStateException("TLS negotiation with the listener failed", e);
                        }
                    });
            return result[0];
        } finally {
            settings.keySet().forEach(key -> restore(key, previous.get(key)));
        }
    }

    private static Negotiation negotiate(int port, Path trustedCertificate, String... offered) throws IOException {
        try (SSLSocket socket = trustingClient(port, trustedCertificate)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(offered);
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            String protocol = socket.getApplicationProtocol();
            if (!"http/1.1".equals(protocol)) {
                return new Negotiation(protocol, null);
            }
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            writer.write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return new Negotiation(protocol, reader.readLine());
        }
    }

    /** A TLS 1.3 client that trusts exactly {@code trustedCertificate} and dials the loopback listener. */
    private static SSLSocket trustingClient(int port, Path trustedCertificate) throws IOException {
        try {
            X509Certificate trusted;
            try (InputStream in = Files.newInputStream(trustedCertificate)) {
                trusted = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("listener", trusted);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, managers.getTrustManagers(), null);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            return socket;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("client trust could not be built", e);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
