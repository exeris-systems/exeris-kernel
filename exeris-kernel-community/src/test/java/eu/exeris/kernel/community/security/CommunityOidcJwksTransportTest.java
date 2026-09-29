/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JWKS key set is fetched only over a connection that authenticated its server, unless the
 * application asked for plaintext by writing {@code http} (ADR-012 §4, ADR-040 §3).
 *
 * <p>Real carriers and real OpenSSL on the loopback, against a JDK server that serves the testkit's
 * JWKS. Each case reads the carrier's {@code TransportTlsClientPosture} event, so what the provider's
 * engine decided is asserted, not inferred from a fetch that happened to work. The two refusals are
 * the postures under which an {@code AMBIENT} engine dials plaintext: no crypto provider bound where
 * the engine is built, and {@code exeris.transport.tls=false}.
 */
@DisplayName("CommunityOidcIdentityProvider — the transport a JWKS key set is fetched over")
class CommunityOidcJwksTransportTest {

    private static final String POSTURE_EVENT = "eu.exeris.kernel.transport.TransportTlsClientPosture";
    private static final String TLS_PROPERTY = "exeris.transport.tls";
    private static final String TRUST_PROPERTY = "exeris.crypto.tls.client.trustFile";
    private static final String JWKS_PATH = "/realms/exeris/protocol/openid-connect/certs";
    private static final String LOOPBACK = "127.0.0.1";

    @TempDir
    static Path material;

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider community;
    private static TlsTestAuthority authority;
    private static TlsTestAuthority.Issued leaf;
    private static byte[] jwks;

    private final Map<String, String> saved = new HashMap<>();
    private final AtomicInteger jwksRequests = new AtomicInteger();

    @BeforeAll
    static void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        community = new CommunityKernelCryptoProvider();
        authority = TlsTestAuthority.root(material, "jwks-ca");
        leaf = authority.issue(TlsTestAuthority.ip(LOOPBACK));
        jwks = new JWKSet(new RSAKey.Builder(TestJwt.testPublicKey()).keyID(TestJwt.TEST_KID).build())
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @BeforeEach
    void saveProperties() {
        for (String key : List.of(TLS_PROPERTY, TRUST_PROPERTY)) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
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
    @DisplayName("https with no crypto provider bound builds no provider, where AMBIENT would dial plaintext")
    void httpsWithNoCryptoProviderIsRefused() throws Exception {
        HttpsServer server = httpsServer();
        try {
            ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator);

            List<RecordedEvent> postures = postures(() -> {
                assertThatThrownBy(() -> scope.call(() -> build(httpsUri(server))))
                        .isInstanceOf(TransportException.class)
                        .satisfies(e -> assertThat(((TransportException) e).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_NET_4004));
                return null;
            });

            assertThat(postures).singleElement().satisfies(posture -> {
                assertThat(posture.getString("requirement")).isEqualTo("VERIFIED");
                assertThat(posture.getString("posture")).isEqualTo("REFUSED_NO_CRYPTO_PROVIDER");
            });
            assertThat(jwksRequests).as("no key set was fetched from the endpoint").hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("https under exeris.transport.tls=false builds no provider, even with a crypto provider bound")
    void httpsWithTlsDeclinedIsRefused() throws Exception {
        System.setProperty(TLS_PROPERTY, "false");
        HttpsServer server = httpsServer();
        try {
            List<RecordedEvent> postures = postures(() -> {
                assertThatThrownBy(() -> trusting().call(() -> build(httpsUri(server))))
                        .isInstanceOf(TransportException.class)
                        .satisfies(e -> assertThat(((TransportException) e).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_NET_4004));
                return null;
            });

            assertThat(postures).singleElement().satisfies(posture -> {
                assertThat(posture.getString("requirement")).isEqualTo("VERIFIED");
                assertThat(posture.getString("posture")).isEqualTo("REFUSED_DECLINED");
            });
            assertThat(jwksRequests).as("no key set was fetched from the endpoint").hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("https with the Community crypto provider bound fetches over verified TLS and authenticates")
    void httpsWithCryptoProviderVerifiesAndAuthenticates() throws Exception {
        HttpsServer server = httpsServer();
        try {
            List<RecordedEvent> postures = postures(() -> trusting().call(() -> {
                try (CommunityOidcIdentityProvider provider = build(httpsUri(server))) {
                    assertAuthenticates(provider);
                }
                return null;
            }));

            assertThat(postures).singleElement().satisfies(posture -> {
                assertThat(posture.getString("requirement")).isEqualTo("VERIFIED");
                assertThat(posture.getString("posture")).isEqualTo("VERIFIED");
            });
            assertThat(jwksRequests).as("the key set came from the endpoint").hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("http is plaintext by request, even with a crypto provider bound")
    void httpIsPlaintextByRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0);
        serveJwks(server);
        server.start();
        try {
            URI uri = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + JWKS_PATH);

            List<RecordedEvent> postures = postures(() -> trusting().call(() -> {
                try (CommunityOidcIdentityProvider provider = build(uri)) {
                    assertAuthenticates(provider);
                }
                return null;
            }));

            assertThat(postures).singleElement().satisfies(posture -> {
                assertThat(posture.getString("requirement")).isEqualTo("PLAINTEXT");
                assertThat(posture.getString("posture")).isEqualTo("PLAINTEXT_REQUIRED");
            });
            assertThat(jwksRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private static CommunityOidcIdentityProvider build(URI jwksUri) {
        return CommunityOidcIdentityProvider.overJwksEndpoint(jwksUri, Map.of(), KeyRotationPolicy.defaults(),
                Clock.systemUTC(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    private static void assertAuthenticates(CommunityOidcIdentityProvider provider) {
        try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
            AuthenticationResult result = provider.authenticate(token);
            assertThat(result.principal().principalId()).isEqualTo(UUID.fromString(TestJwt.TEST_SUBJECT));
        }
    }

    /** The scope the kernel builds a provider in: allocator, Community crypto, and trust in the test authority. */
    private static ScopedValue.Carrier trusting() {
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, community)
                .where(KernelProviders.CURRENT_CONFIG, new MapConfigProvider(
                        Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, authority.certificate().toString()),
                        Map.of()));
    }

    private HttpsServer httpsServer() throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext()));
        serveJwks(server);
        server.start();
        return server;
    }

    private void serveJwks(HttpServer server) {
        server.createContext(JWKS_PATH, exchange -> {
            jwksRequests.incrementAndGet();
            try (InputStream ignored = exchange.getRequestBody(); OutputStream out = exchange.getResponseBody()) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                out.write(jwks);
            }
        });
    }

    private static URI httpsUri(HttpsServer server) {
        return URI.create("https://" + LOOPBACK + ":" + server.getAddress().getPort() + JWKS_PATH);
    }

    private static SSLContext serverContext() throws Exception {
        Certificate certificate;
        try (InputStream in = Files.newInputStream(leaf.certificate())) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        String pem = Files.readString(leaf.privateKey())
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        char[] password = "changeit".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("leaf", key, password, new Certificate[] {certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static List<RecordedEvent> postures(Callable<?> action) throws Exception {
        Path dump = Files.createTempFile(material, "posture", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(POSTURE_EVENT).withoutStackTrace();
            recording.start();
            try {
                action.call();
            } finally {
                recording.stop();
                recording.dump(dump);
            }
        }
        return RecordingFile.readAllEvents(dump).stream()
                .filter(event -> POSTURE_EVENT.equals(event.getEventType().getName()))
                .toList();
    }
}
