/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static eu.exeris.kernel.community.bootstrap.CommunityJwksSettings.AUDIENCE_KEY;
import static eu.exeris.kernel.community.bootstrap.CommunityJwksSettings.ISSUER_KEY;
import static eu.exeris.kernel.community.bootstrap.CommunityJwksSettings.JWKS_URI_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token verification keys come from configuration: the three keys together arm the Community provider
 * over a JWKS endpoint, built at {@code start()} inside the kernel scope; none of them leaves it
 * holding no key; any other combination fails the boot naming what is missing.
 */
@DisplayName("CommunitySecuritySubsystem — verification keys from configuration")
class CommunitySecuritySubsystemJwksTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String JWKS_PATH = "/jwks";
    private static final String HTTP_URI = "http://" + LOOPBACK + ":1" + JWKS_PATH;

    private static MemoryAllocator allocator;

    @BeforeAll
    static void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @Nested
    @DisplayName("Reading the keys")
    class Reading {

        @Test
        @DisplayName("none of the three keys set reads as no JWKS configuration")
        void noneSet() {
            assertThat(CommunityJwksSettings.read(config(Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("blank values read as unset")
        void blankIsUnset() {
            assertThat(CommunityJwksSettings.read(
                    config(Map.of(JWKS_URI_KEY, " ", ISSUER_KEY, "", AUDIENCE_KEY, "\t")))).isEmpty();
        }

        @Test
        @DisplayName("all three keys set read as the JWKS settings, values stripped")
        void allSet() {
            assertThat(CommunityJwksSettings.read(config(Map.of(
                    JWKS_URI_KEY, " " + HTTP_URI + " ", ISSUER_KEY, "issuer", AUDIENCE_KEY, "audience"))))
                    .hasValueSatisfying(settings -> {
                        assertThat(settings.jwksUri()).hasToString(HTTP_URI);
                        assertThat(settings.issuer()).isEqualTo("issuer");
                        assertThat(settings.audience()).isEqualTo("audience");
                    });
        }

        @ParameterizedTest(name = "{0} missing")
        @ValueSource(strings = {JWKS_URI_KEY, ISSUER_KEY, AUDIENCE_KEY})
        @DisplayName("any one key missing while another is set refuses, naming the missing key")
        void oneMissingRefusesNamingIt(String missing) {
            Map<String, String> keys = new HashMap<>(Map.of(
                    JWKS_URI_KEY, HTTP_URI, ISSUER_KEY, "issuer", AUDIENCE_KEY, "audience"));
            keys.remove(missing);

            assertThatThrownBy(() -> CommunityJwksSettings.read(config(keys)))
                    .isInstanceOfSatisfying(SubsystemException.class, ex -> {
                        assertThat(ex.phase()).isEqualTo(SubsystemException.Phase.INITIALIZE);
                        assertThat(ex.detail()).startsWith(missing + " is not set");
                    });
        }

        @ParameterizedTest(name = "only {0} set")
        @ValueSource(strings = {JWKS_URI_KEY, ISSUER_KEY, AUDIENCE_KEY})
        @DisplayName("a single key on its own refuses too")
        void singleKeyRefuses(String only) {
            assertThatThrownBy(() -> CommunityJwksSettings.read(
                    config(Map.of(only, JWKS_URI_KEY.equals(only) ? HTTP_URI : "value"))))
                    .isInstanceOf(SubsystemException.class);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ftp://" + LOOPBACK + "/jwks", "/jwks", "http:///jwks", "http://exa mple/jwks"})
        @DisplayName("a JWKS URI that is not absolute http(s) with a host refuses")
        void badUriRefuses(String uri) {
            assertThatThrownBy(() -> CommunityJwksSettings.read(config(Map.of(
                    JWKS_URI_KEY, uri, ISSUER_KEY, "issuer", AUDIENCE_KEY, "audience"))))
                    .isInstanceOfSatisfying(SubsystemException.class, ex -> {
                        assertThat(ex.phase()).isEqualTo(SubsystemException.Phase.INITIALIZE);
                        assertThat(ex.detail()).startsWith(JWKS_URI_KEY);
                    });
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("with no keys the ServiceLoader provider is bound and denies every token")
        void noKeysBindsKeylessProvider() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();
            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config(Map.of())).run(subsystem::initialize);
            subsystem.start();

            SecurityProvider bound = boundProvider(subsystem);
            assertThat(bound).isInstanceOf(CommunitySecurityProvider.class);
            try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
                assertThatThrownBy(() -> bound.authenticate(token))
                        .isInstanceOf(SecurityAuthenticationException.class);
            }
            subsystem.stop();
        }

        @Test
        @DisplayName("a partial configuration fails initialize()")
        void partialFailsInitialize() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();

            assertThatThrownBy(() -> ScopedValue.where(KernelProviders.CURRENT_CONFIG,
                            config(Map.of(JWKS_URI_KEY, HTTP_URI, ISSUER_KEY, "issuer")))
                    .run(subsystem::initialize))
                    .isInstanceOfSatisfying(SubsystemException.class,
                            ex -> assertThat(ex.detail()).startsWith(AUDIENCE_KEY));
        }

        @Test
        @DisplayName("an https endpoint with no crypto provider bound fails start()")
        void httpsWithoutCryptoFailsStart() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();
            ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .where(KernelProviders.CURRENT_CONFIG, config(Map.of(
                            JWKS_URI_KEY, "https://" + LOOPBACK + ":1" + JWKS_PATH,
                            ISSUER_KEY, TestJwt.EXPECTED_ISSUER, AUDIENCE_KEY, TestJwt.EXPECTED_AUDIENCE)));
            scope.run(subsystem::initialize);

            assertThatThrownBy(() -> scope.run(subsystem::start))
                    .isInstanceOfSatisfying(SubsystemException.class, ex -> {
                        assertThat(ex.phase()).isEqualTo(SubsystemException.Phase.START);
                        assertThat(ex.detail()).contains(JWKS_URI_KEY);
                    });
            assertThat(subsystem.isRunning()).isFalse();
        }

        @Test
        @DisplayName("start() builds the key set after the JFR warm-up, its last fallible step")
        void keySetIsBuiltAfterTheWarmUp() {
            JavaMethod start = new ClassFileImporter().importClasses(CommunitySecuritySubsystem.class)
                    .get(CommunitySecuritySubsystem.class).getMethod("start");

            int warmUp = firstCallLine(start, CommunityJfrEventCatalogue.class, "warmHotPath");
            int keySet = firstCallLine(start, DeferredSecurityProvider.class, "start");

            assertThat(keySet)
                    .as("shutdown() stops only a running subsystem: a warm-up that failed after the key "
                            + "set was built would leave its client engine open with nothing to close it")
                    .isGreaterThan(warmUp);
        }

        @Test
        @DisplayName("all three keys: the bound provider verifies against the endpoint, and stop() closes it")
        void configuredProviderAuthenticatesUntilStop() throws Exception {
            byte[] jwks = new JWKSet(new RSAKey.Builder(TestJwt.testPublicKey()).keyID(TestJwt.TEST_KID).build())
                    .toString().getBytes(StandardCharsets.UTF_8);
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0);
            server.createContext(JWKS_PATH, exchange -> {
                requests.incrementAndGet();
                try (var _ = exchange.getRequestBody(); OutputStream out = exchange.getResponseBody()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    out.write(jwks);
                }
            });
            server.start();
            try {
                String uri = "http://" + LOOPBACK + ":" + server.getAddress().getPort() + JWKS_PATH;
                ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                        .where(KernelProviders.CURRENT_CONFIG, config(Map.of(JWKS_URI_KEY, uri,
                                ISSUER_KEY, TestJwt.EXPECTED_ISSUER, AUDIENCE_KEY, TestJwt.EXPECTED_AUDIENCE)));
                CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();
                scope.run(subsystem::initialize);
                scope.run(subsystem::start);

                SecurityProvider bound = boundProvider(subsystem);
                assertThat(bound.providerId()).isEqualTo("jwt-community");
                try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
                    AuthenticationResult result = bound.authenticate(token);
                    assertThat(result.principal().principalId()).isEqualTo(UUID.fromString(TestJwt.TEST_SUBJECT));
                }
                assertThat(requests).as("the key set is fetched from the configured endpoint").hasValue(1);

                subsystem.stop();
                assertThat(subsystem.isRunning()).isFalse();
                try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
                    assertThatThrownBy(() -> bound.authenticate(token))
                            .as("a closed provider denies rather than admitting")
                            .isInstanceOf(SecurityAuthenticationException.class);
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private static int firstCallLine(JavaMethod method, Class<?> owner, String name) {
        return method.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isEquivalentTo(owner) && name.equals(call.getName()))
                .mapToInt(JavaMethodCall::getLineNumber)
                .min()
                .orElseThrow(() -> new AssertionError(method.getFullName() + " never calls "
                        + owner.getSimpleName() + "." + name));
    }

    private static MapConfigProvider config(Map<String, String> strings) {
        return new MapConfigProvider(strings, Map.of());
    }

    private static SecurityProvider boundProvider(CommunitySecuritySubsystem subsystem) {
        AtomicReference<SecurityProvider> bound = new AtomicReference<>();
        subsystem.providerBindings()
                .apply(ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, null))
                .run(() -> bound.set(KernelProviders.SECURITY_PROVIDER.get()));
        return bound.get();
    }
}
