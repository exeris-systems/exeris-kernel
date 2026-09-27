/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link NativeTcpTransportProvider} decides for a carrier's outbound connections, read from the
 * {@code TransportTlsClientPosture} event it records: where the trust comes from, when outbound TLS is
 * not armed, what a crypto provider that cannot verify a peer does to each mode, and what a
 * {@link CommunityOutboundTls} requirement holds the decision to.
 */
@DisplayName("NativeTcpTransportProvider — the client TLS decision and its posture event")
class NativeTcpTransportProviderClientTlsTest {

    private static final String POSTURE_EVENT = "eu.exeris.kernel.transport.TransportTlsClientPosture";
    private static final String TLS_PROPERTY = "exeris.transport.tls";
    private static final String TRUST_PROPERTY = "exeris.crypto.tls.client.trustFile";

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider community;

    @TempDir
    static Path material;

    private static Path anchor;

    private final Map<String, String> saved = new HashMap<>();

    @BeforeAll
    static void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        community = new CommunityKernelCryptoProvider();
        anchor = TlsTestAuthority.root(material, "posture-ca").certificate();
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
    @DisplayName("the configuration key names the trust, ahead of the system property")
    void configKeyNamesTheTrust() throws Exception {
        System.setProperty(TRUST_PROPERTY, material.resolve("absent-but-overridden.pem").toString());

        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, community, trustKey(anchor)));

        assertPosture(posture, "CLIENT", "VERIFIED", "CONFIG_KEY");
        assertThat(posture.getString("requirement"))
                .as("TransportProvider#createEngine states no requirement")
                .isEqualTo("AMBIENT");
        assertThat(posture.getBoolean("configBound")).isTrue();
        assertThat(posture.getString("defaultCertFile")).isNull();
    }

    @Test
    @DisplayName("with no configuration bound, the system property names the trust")
    void systemPropertyNamesTheTrustWhenNoConfigIsBound() throws Exception {
        System.setProperty(TRUST_PROPERTY, anchor.toString());

        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, community, null));

        assertPosture(posture, "CLIENT", "VERIFIED", "SYSTEM_PROPERTY");
        assertThat(posture.getBoolean("configBound")).isFalse();
    }

    @Test
    @DisplayName("with neither, the trust is OpenSSL's default, reported by location")
    void neitherMeansTheSystemDefault() throws Exception {
        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, community, emptyConfig()));

        assertPosture(posture, "CLIENT", "VERIFIED", "SYSTEM_DEFAULT");
        assertThat(posture.getString("defaultCertFile")).isNotBlank();
        assertThat(posture.getString("defaultCertDir")).isNotBlank();
    }

    @Test
    @DisplayName("no crypto provider bound: plaintext, and said so")
    void noProviderDialsPlaintext() throws Exception {
        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, null, trustKey(anchor)));

        assertPosture(posture, "CLIENT", "PLAINTEXT_NO_CRYPTO_PROVIDER", "NONE");
    }

    @Test
    @DisplayName("exeris.transport.tls=false: plaintext, and said so")
    void optOutDialsPlaintext() throws Exception {
        System.setProperty(TLS_PROPERTY, "false");

        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, community, trustKey(anchor)));

        assertPosture(posture, "CLIENT", "PLAINTEXT_DECLINED", "NONE");
    }

    @Test
    @DisplayName("a DUAL listener without material dials plaintext")
    void dualWithoutMaterialDialsPlaintext() throws Exception {
        RecordedEvent posture = onePosture(() -> build(TransportMode.DUAL, community, trustKey(anchor)));

        assertPosture(posture, "DUAL", "PLAINTEXT_NO_LISTENER_MATERIAL", "NONE");
    }

    @Test
    @DisplayName("a foreign provider fails a CLIENT carrier's construction, and the refusal is recorded")
    void foreignProviderFailsAClientCarrier() throws Exception {
        List<RecordedEvent> postures = postures(() -> {
            assertThatThrownBy(() -> build(TransportMode.CLIENT, new ForeignCryptoProvider(), emptyConfig()))
                    .isInstanceOf(TransportException.class)
                    .satisfies(e -> {
                        assertThat(((TransportException) e).errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4004);
                        assertThat(((TransportException) e).rawArgs())
                                .contains("bound crypto provider cannot verify an outbound peer");
                    });
            return null;
        });

        assertThat(postures).hasSize(1);
        assertPosture(postures.getFirst(), "CLIENT", "REFUSED_FOREIGN_PROVIDER", "NONE");
    }

    @Test
    @DisplayName("a foreign provider with TLS declined builds a plaintext CLIENT carrier")
    void foreignProviderWithOptOutBuilds() throws Exception {
        System.setProperty(TLS_PROPERTY, "false");

        RecordedEvent posture = onePosture(() -> build(TransportMode.CLIENT, new ForeignCryptoProvider(), emptyConfig()));

        assertPosture(posture, "CLIENT", "PLAINTEXT_DECLINED", "NONE");
    }

    @Test
    @DisplayName("a foreign provider builds a SERVER carrier, which dials nothing and records no posture")
    void foreignProviderBuildsAServer() throws Exception {
        assertThat(postures(() -> build(TransportMode.SERVER, new ForeignCryptoProvider(), emptyConfig())))
                .isEmpty();
    }

    @Test
    @DisplayName("a foreign provider builds a DUAL carrier, whose connect is refused before any socket opens")
    void foreignProviderRefusesADualConnect(@TempDir Path dualMaterial) throws Exception {
        TlsTestCertificate own = TlsTestCertificate.generateInto(dualMaterial);
        TransportConfig config = new TransportConfig(TransportMode.DUAL, "127.0.0.1", freePort(), 1,
                own.certPath(), own.keyPath(), 1024, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        RecordedEvent posture = onePosture(() -> {
            holder[0] = within(new ForeignCryptoProvider(), emptyConfig(),
                    () -> new NativeTcpTransportProvider().createEngine(config));
            return holder[0];
        });
        assertPosture(posture, "DUAL", "REFUSED_FOREIGN_PROVIDER", "NONE");

        try (TransportEngine dual = holder[0]) {
            dual.setStreamHandler(stream -> { });
            dual.start();
            assertThatThrownBy(() -> dual.connect("127.0.0.1", freePort()))
                    .isInstanceOf(TransportException.class)
                    .satisfies(e -> {
                        assertThat(((TransportException) e).errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4001);
                        assertThat(e.getCause()).isInstanceOf(TlsHandshakeException.class);
                        assertThat(((TlsHandshakeException) e.getCause()).rawArgs())
                                .containsExactly(-1, TlsFailureDetail.NO_PEER_VERIFIER);
                    });
        }
    }

    @Test
    @DisplayName("a trust file that is not a readable file fails construction, naming the key, even with TLS declined")
    void unusableTrustFileFailsConstruction() {
        System.setProperty(TLS_PROPERTY, "false");
        Path missing = material.resolve("missing.pem");

        assertThatThrownBy(() -> build(TransportMode.CLIENT, community, trustKey(missing)))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> {
                    TransportException failure = (TransportException) e;
                    assertThat(failure.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4004);
                    assertThat(failure.rawArgs()).contains("crypto.tls.client.trustFile cannot be used as client trust");
                    assertThat(failure.getCause()).isInstanceOf(CryptoBootstrapException.class);
                    assertThat(((CryptoBootstrapException) failure.getCause()).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_NET_2002);
                });
    }

    @Test
    @DisplayName("PLAINTEXT required: plaintext although the Community provider is bound, and said so")
    void plaintextRequirementIgnoresABoundCommunityProvider() throws Exception {
        RecordedEvent posture = onePosture(() ->
                build(TransportMode.CLIENT, community, trustKey(anchor), CommunityOutboundTls.PLAINTEXT));

        assertPosture(posture, "CLIENT", "PLAINTEXT_REQUIRED", "NONE");
        assertThat(posture.getString("requirement")).isEqualTo("PLAINTEXT");
    }

    @Test
    @DisplayName("PLAINTEXT required: a foreign provider builds the carrier")
    void plaintextRequirementBuildsUnderAForeignProvider() throws Exception {
        RecordedEvent posture = onePosture(() ->
                build(TransportMode.CLIENT, new ForeignCryptoProvider(), emptyConfig(),
                        CommunityOutboundTls.PLAINTEXT));

        assertPosture(posture, "CLIENT", "PLAINTEXT_REQUIRED", "NONE");
    }

    @Test
    @DisplayName("PLAINTEXT required: a trust file that is not a readable file still fails construction")
    void plaintextRequirementStillChecksTheTrustFile() {
        Path missing = material.resolve("missing-under-plaintext.pem");

        assertThatThrownBy(() ->
                build(TransportMode.CLIENT, community, trustKey(missing), CommunityOutboundTls.PLAINTEXT))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(((TransportException) e).rawArgs())
                        .contains("crypto.tls.client.trustFile cannot be used as client trust"));
    }

    @Test
    @DisplayName("VERIFIED required with the Community provider bound: a verifying carrier")
    void verifiedRequirementWithTheCommunityProviderVerifies() throws Exception {
        RecordedEvent posture = onePosture(() ->
                build(TransportMode.CLIENT, community, trustKey(anchor), CommunityOutboundTls.VERIFIED));

        assertPosture(posture, "CLIENT", "VERIFIED", "CONFIG_KEY");
        assertThat(posture.getString("requirement")).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VERIFIED required with no provider bound: construction fails, and the refusal is recorded")
    void verifiedRequirementWithoutAProviderIsRefused() throws Exception {
        assertRefusedAndRecorded(null, NativeTcpClientTlsResolver.VERIFIED_BUT_NO_CRYPTO_PROVIDER,
                "REFUSED_NO_CRYPTO_PROVIDER");
    }

    @Test
    @DisplayName("VERIFIED required under exeris.transport.tls=false: construction fails, neither downgraded "
            + "nor armed")
    void verifiedRequirementUnderTheOptOutIsRefused() throws Exception {
        System.setProperty(TLS_PROPERTY, "false");

        assertRefusedAndRecorded(community, NativeTcpClientTlsResolver.VERIFIED_BUT_DECLINED, "REFUSED_DECLINED");
    }

    @Test
    @DisplayName("VERIFIED required with a foreign provider bound: construction fails, and the refusal is recorded")
    void verifiedRequirementWithAForeignProviderIsRefused() throws Exception {
        assertRefusedAndRecorded(new ForeignCryptoProvider(), TlsFailureDetail.NO_PEER_VERIFIER,
                "REFUSED_FOREIGN_PROVIDER");
    }

    @Test
    @DisplayName("a requirement on a SERVER or DUAL transport is refused before anything is built")
    void aRequirementOnANonClientTransportIsRefused() throws Exception {
        for (TransportMode mode : List.of(TransportMode.SERVER, TransportMode.DUAL)) {
            for (CommunityOutboundTls requirement
                    : List.of(CommunityOutboundTls.PLAINTEXT, CommunityOutboundTls.VERIFIED)) {
                List<RecordedEvent> postures = postures(() -> {
                    assertThatThrownBy(() -> build(mode, community, trustKey(anchor), requirement))
                            .as("%s with %s", mode, requirement)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("CLIENT transport only");
                    return null;
                });
                assertThat(postures).as("%s with %s records nothing", mode, requirement).isEmpty();
            }
        }
    }

    private void assertRefusedAndRecorded(KernelCryptoProvider provider, String reason, String refusal)
            throws Exception {
        List<RecordedEvent> postures = postures(() -> {
            assertThatThrownBy(() ->
                    build(TransportMode.CLIENT, provider, trustKey(anchor), CommunityOutboundTls.VERIFIED))
                    .isInstanceOf(TransportException.class)
                    .satisfies(e -> {
                        TransportException failure = (TransportException) e;
                        assertThat(failure.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4004);
                        assertThat(failure.rawArgs()).contains(reason);
                        assertThat(failure.getCause()).isNull();
                    });
            return null;
        });

        assertThat(postures).as("the refusal is recorded once").hasSize(1);
        assertPosture(postures.getFirst(), "CLIENT", refusal, "NONE");
        assertThat(postures.getFirst().getString("requirement")).isEqualTo("VERIFIED");
    }

    private static void assertPosture(RecordedEvent posture, String mode, String decision, String trustSource) {
        assertThat(posture.getString("transportMode")).isEqualTo(mode);
        assertThat(posture.getString("posture")).isEqualTo(decision);
        assertThat(posture.getString("trustSource")).isEqualTo(trustSource);
    }

    private static TransportEngine build(TransportMode mode, KernelCryptoProvider provider, ConfigProvider config)
            throws Exception {
        TransportConfig transportConfig = transportConfig(mode);
        TransportEngine engine = within(provider, config, () -> new NativeTcpTransportProvider().createEngine(transportConfig));
        engine.close();
        return engine;
    }

    private static TransportEngine build(TransportMode mode, KernelCryptoProvider provider, ConfigProvider config,
                                         CommunityOutboundTls requirement) throws Exception {
        TransportConfig transportConfig = transportConfig(mode);
        TransportEngine engine = within(provider, config,
                () -> new NativeTcpTransportProvider().createEngine(transportConfig, requirement));
        engine.close();
        return engine;
    }

    private static TransportConfig transportConfig(TransportMode mode) throws IOException {
        return new TransportConfig(mode, "127.0.0.1",
                mode == TransportMode.CLIENT ? 0 : freePort(), 1, null, null, 1024, 30_000);
    }

    private static TransportEngine within(KernelCryptoProvider provider, ConfigProvider config,
                                          Callable<TransportEngine> build) throws Exception {
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator);
        if (provider != null) {
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, provider);
        }
        if (config != null) {
            scope = scope.where(KernelProviders.CURRENT_CONFIG, config);
        }
        return scope.call(build::call);
    }

    private static RecordedEvent onePosture(Callable<?> action) throws Exception {
        List<RecordedEvent> postures = postures(action);
        assertThat(postures).as("one posture per CLIENT or DUAL carrier").hasSize(1);
        return postures.getFirst();
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

    private static ConfigProvider trustKey(Path trustFile) {
        return new MapConfigProvider(
                Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, trustFile.toString()), Map.of());
    }

    private static ConfigProvider emptyConfig() {
        return new MapConfigProvider(Map.of(), Map.of());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A bound provider that is not the Community one, so it cannot verify an outbound peer. */
    private static final class ForeignCryptoProvider implements KernelCryptoProvider {

        @Override
        public TlsEngine createTlsEngine(CryptoProviderConfig config) {
            throw new UnsupportedOperationException("never asked for an engine in these cases");
        }

        @Override
        public boolean supportsQuic() {
            return false;
        }

        @Override
        public String providerName() {
            return "foreign";
        }

        @Override
        public int priority() {
            return 1;
        }
    }
}
