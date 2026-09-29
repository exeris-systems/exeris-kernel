/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kernel booted in {@code HttpMode.DUAL} reaches its own listener through the client engine it
 * binds at {@code HTTP_CLIENT_ENGINE}.
 *
 * <p>{@code DUAL} builds a server engine and a client engine from one {@code HttpConfig}. Each
 * engine's transport takes that engine's role: the client's is a {@code CLIENT} transport with no
 * listener, which is what lets it start without a stream handler and dial out.
 *
 * <p>Booted with crypto and listener material, the client engine speaks TLS and verifies the
 * kernel's own certificate against {@code crypto.tls.client.trustFile}; without that key it verifies
 * against OpenSSL's default trust, which does not hold a self-signed certificate.
 *
 * <p>A request that names no peer goes to {@code http.client.defaultAuthority}, and over TLS the
 * client verifies the host of that authority, not the address the connection reached: the cases that
 * name the listener {@code localhost} while the connection reports {@code 127.0.0.1} are refused by a
 * certificate that covers only the other of the two.
 */
@Timeout(60)
@DisplayName("Community: a DUAL kernel dials its own listener through HTTP_CLIENT_ENGINE")
class CommunityHttpDualSelfDialTest {

    private static final int X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT = 18;
    private static final int X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20;
    private static final int X509_V_ERR_HOSTNAME_MISMATCH = 62;
    private static final int X509_V_ERR_IP_ADDRESS_MISMATCH = 64;
    private static final String DEFAULT_AUTHORITY_PROPERTY = "exeris.http.client.defaultAuthority";

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void rememberProperties() {
        for (String key : List.of("exeris.http.mode", "exeris.http.bindHost", "exeris.http.port",
                "exeris.transport.certPath", "exeris.transport.keyPath", "exeris.crypto.tls.client.trustFile",
                DEFAULT_AUTHORITY_PROPERTY)) {
            saved.put(key, System.getProperty(key));
        }
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    @DisplayName("plaintext DUAL: HTTP_CLIENT_ENGINE.send to the kernel's own listener returns 200")
    void plaintextDualKernelReachesItsOwnListener() throws Exception {
        int port = nextFreePort();
        System.setProperty("exeris.http.mode", "DUAL");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        AtomicInteger status = new AtomicInteger();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("http"))
                .build()
                .boot(() -> status.set(getHealth(port)));

        assertThat(status.get())
                .as("the DUAL kernel's client engine reached its own /health")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("TLS DUAL with the client trust key: the client verifies the kernel's own certificate and gets 200")
    void tlsDualKernelVerifiesItsOwnListener(@TempDir Path material) throws Exception {
        assertThat(new CommunityKernelCryptoProvider()).as("OpenSSL loads").isNotNull();
        int port = tlsDual(material, true);
        AtomicInteger status = new AtomicInteger();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("crypto", "http"))
                .build()
                .boot(() -> status.set(getHealth(port)));

        assertThat(status.get())
                .as("the TLS DUAL kernel's client engine verified and reached its own /health")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("TLS DUAL without the client trust key: the default trust refuses the self-signed certificate")
    void tlsDualKernelWithDefaultTrustRefusesItsOwnListener(@TempDir Path material) throws Exception {
        assertThat(new CommunityKernelCryptoProvider()).as("OpenSSL loads").isNotNull();
        int port = tlsDual(material, false);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("crypto", "http"))
                .build()
                .boot(() -> {
                    try {
                        getHealth(port);
                    } catch (RuntimeException failure) {
                        thrown.set(failure);
                    }
                });

        assertThat(thrown.get())
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(failure -> {
                    TlsHandshakeException refusal = (TlsHandshakeException) failure;
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
                    assertThat(refusal.rawArgs())
                            .containsExactly(X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT,
                                    TlsFailureDetail.PEER_VERIFICATION_FAILED);
                });
    }

    @Test
    @DisplayName("plaintext DUAL: an unaddressed request reaches the kernel's own listener through the default authority")
    void plaintextDualKernelReachesItsOwnListenerThroughTheDefaultAuthority() throws Exception {
        int port = nextFreePort();
        System.setProperty("exeris.http.mode", "DUAL");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty(DEFAULT_AUTHORITY_PROPERTY, "127.0.0.1:" + port);
        AtomicReference<String> reported = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("http"))
                .build()
                .boot(() -> {
                    reported.set(HttpKernelProviders.HTTP_CLIENT_ENGINE.get().defaultAuthority());
                    status.set(getHealthUnaddressed());
                });

        assertThat(reported.get()).as("the bound client engine reports the configured default")
                .isEqualTo("127.0.0.1:" + port);
        assertThat(status.get()).as("the unaddressed request reached the kernel's own /health").isEqualTo(200);
    }

    @Test
    @DisplayName("TLS DUAL, default authority by name: the client verifies the name and gets 200")
    void tlsDualKernelVerifiesTheDefaultAuthorityName(@TempDir Path material) throws Exception {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "dual-default-ca");
        tlsDualByDefaultAuthority(authority.issue(TlsTestAuthority.dns("localhost")),
                authority.certificate(), "localhost");

        assertThat(bootAndSend(CommunityHttpDualSelfDialTest::getHealthUnaddressed))
                .as("verified against the default authority's host and reached /health")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("TLS DUAL, default authority by name: a certificate naming only the reached address is refused with 62")
    void tlsDualKernelRefusesACertificateForTheAddressWhenTheDefaultNamesAHost(@TempDir Path material)
            throws Exception {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "dual-name-ca");
        tlsDualByDefaultAuthority(authority.issue(TlsTestAuthority.ip("127.0.0.1")),
                authority.certificate(), "localhost");

        assertRefused(X509_V_ERR_HOSTNAME_MISMATCH);
    }

    @Test
    @DisplayName("TLS DUAL, default authority by address: a certificate naming only the host name is refused with 64")
    void tlsDualKernelRefusesACertificateForTheNameWhenTheDefaultNamesAnAddress(@TempDir Path material)
            throws Exception {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "dual-address-ca");
        tlsDualByDefaultAuthority(authority.issue(TlsTestAuthority.dns("localhost")),
                authority.certificate(), "127.0.0.1");

        assertRefused(X509_V_ERR_IP_ADDRESS_MISMATCH);
    }

    @Test
    @DisplayName("TLS DUAL, default authority: a listener from an issuer the trust key does not hold is refused with 20")
    void tlsDualKernelRefusesAnUntrustedListenerAtTheDefaultAuthority(@TempDir Path material) throws Exception {
        TlsTestAuthority trusted = TlsTestAuthority.root(material, "dual-trusted-ca");
        TlsTestAuthority other = TlsTestAuthority.root(material, "dual-other-ca");
        tlsDualByDefaultAuthority(other.issue(TlsTestAuthority.dns("localhost")),
                trusted.certificate(), "localhost");

        assertRefused(X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY);
    }

    /**
     * Configures a TLS DUAL boot whose listener presents {@code leaf}, whose client trusts
     * {@code trustFile}, and whose default authority is {@code host} and the listener's port.
     */
    private void tlsDualByDefaultAuthority(TlsTestAuthority.Issued leaf, Path trustFile, String host)
            throws java.io.IOException {
        int port = nextFreePort();
        System.setProperty("exeris.http.mode", "DUAL");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.transport.certPath", leaf.certificate().toString());
        System.setProperty("exeris.transport.keyPath", leaf.privateKey().toString());
        System.setProperty("exeris.crypto.tls.client.trustFile", trustFile.toString());
        System.setProperty(DEFAULT_AUTHORITY_PROPERTY, host + ":" + port);
    }

    private static int bootAndSend(IntSupplier send) throws Exception {
        assertThat(new CommunityKernelCryptoProvider()).as("OpenSSL loads").isNotNull();
        AtomicInteger status = new AtomicInteger();
        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("crypto", "http"))
                .build()
                .boot(() -> status.set(send.getAsInt()));
        return status.get();
    }

    private static void assertRefused(int x509Code) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        bootAndSend(() -> {
            try {
                return getHealthUnaddressed();
            } catch (RuntimeException failure) {
                thrown.set(failure);
                return -1;
            }
        });

        assertThat(thrown.get())
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(failure -> {
                    TlsHandshakeException refusal = (TlsHandshakeException) failure;
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
                    assertThat(refusal.rawArgs())
                            .containsExactly(x509Code, TlsFailureDetail.PEER_VERIFICATION_FAILED);
                });
    }

    private static int getHealthUnaddressed() {
        HttpClientEngine engine = HttpKernelProviders.HTTP_CLIENT_ENGINE.get();
        HttpResponse response = engine.send(
                HttpRequest.noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of()));
        if (response.body() != null) {
            response.body().close();
        }
        return response.status().code();
    }

    /** Configures a TLS DUAL boot on a fresh port through the kernel's system-property keys. */
    private int tlsDual(Path material, boolean trustTheListener) throws java.io.IOException {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(material);
        int port = nextFreePort();
        System.setProperty("exeris.http.mode", "DUAL");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.transport.certPath", certificate.certificate().toString());
        System.setProperty("exeris.transport.keyPath", certificate.privateKey().toString());
        if (trustTheListener) {
            System.setProperty("exeris.crypto.tls.client.trustFile", certificate.certificate().toString());
        } else {
            System.clearProperty("exeris.crypto.tls.client.trustFile");
        }
        return port;
    }

    private static int getHealth(int port) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of())
                .withAuthority("127.0.0.1:" + port);
        HttpResponse response = HttpKernelProviders.HTTP_CLIENT_ENGINE.get().send(request);
        if (response.body() != null) {
            response.body().close();
        }
        return response.status().code();
    }

    private static int nextFreePort() throws java.io.IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
