/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.http.CommunityTextResponseBodyDecoder;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.ScopedValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test: the Community OIDC {@link CommunityOidcIdentityProvider} validates a <em>real</em>
 * RS256 access token minted by a live Keycloak, fetching the verification keys over HTTP from the
 * realm's JWKS endpoint (ADR-040).
 *
 * <h2>What this proves end-to-end</h2>
 * <ul>
 *   <li>{@link CommunityOidcIdentityProvider#overJwksEndpoint} wires a {@link KernelWebClient} → live
 *       JWKS GET → {@code kid → RSAPublicKey} snapshot → RS256 signature verification.</li>
 *   <li>The raw-text JWKS fetch path ({@link CommunityTextResponseBodyDecoder}) round-trips an
 *       {@code application/json} JWKS document into the JOSE parser — the production
 *       {@code client.get(jwksPath, String.class)} contract.</li>
 *   <li>A verified token yields a populated {@code PrincipalContext} (Keycloak {@code sub} → principal
 *       id) with the default {@code security:read} scope and a non-null isolation strategy.</li>
 *   <li>Issuer binding is live: a provider configured for a foreign issuer denies the same
 *       signature-valid token with a terminal {@code EX-SEC-2002} (fail-closed).</li>
 * </ul>
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Run with:
 * <pre>mvn test -DincludedGroups=integration -DexcludedGroups=</pre>
 * <p>Skipped automatically when Docker is not available.
 *
 * @since 0.10.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: CommunityOidcIdentityProvider against a live Keycloak (Testcontainers)")
class CommunityOidcKeycloakIT {

    private static final String REALM = "exeris";
    private static final String CLIENT_ID = "exeris-kernel-it";
    private static final String AUDIENCE = "exeris-kernel";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "alice-pw";
    private static final String SUBJECT_ID = "11111111-1111-7111-8111-111111111111";
    private static final String JWKS_PATH = "/realms/" + REALM + "/protocol/openid-connect/certs";
    private static final String FOREIGN_ISSUER = "https://foreign.example.com";
    private static final int KC_PORT = 8080;

    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
                    .withExposedPorts(KC_PORT)
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("keycloak/exeris-realm.json"),
                            "/opt/keycloak/data/import/exeris-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                            .forPort(KC_PORT).forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final HttpProvider HTTP = new CommunityHttpProvider();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // JWKS client decodes String.class as raw response text (NOT JSON-object mapping) per the
    // overJwksEndpoint contract; GET carries no body, so the encoder registry stays empty.
    private static final HttpResponseBodyDecoderRegistry TEXT_DECODERS =
            HttpResponseBodyDecoderRegistry.of(
                    List.<HttpResponseBodyDecoder>of(new CommunityTextResponseBodyDecoder()));
    private static final HttpRequestBodyEncoderRegistry NO_ENCODERS =
            HttpRequestBodyEncoderRegistry.of(List.<HttpRequestBodyEncoder>of());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("validates a real Keycloak RS256 token end-to-end via live JWKS fetch")
    void validatesRealKeycloakTokenEndToEnd() throws Exception {
        String accessToken = mintAccessToken();
        SignedJWT parsed = SignedJWT.parse(accessToken);
        String issuer = parsed.getJWTClaimsSet().getIssuer();
        String subject = parsed.getJWTClaimsSet().getSubject();

        assertThat(subject).as("Keycloak sub == imported user id").isEqualTo(SUBJECT_ID);
        assertThat(parsed.getJWTClaimsSet().getAudience())
                .as("audience mapper added the kernel audience").contains(AUDIENCE);

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            try (HttpClientEngine engine = HTTP.createClientEngine(clientConfig())) {
                engine.start();
                KernelWebClient jwksClient = new KernelWebClient(engine, ALLOCATOR, NO_ENCODERS, TEXT_DECODERS);

                CommunityOidcIdentityProvider provider = CommunityOidcIdentityProvider.overJwksEndpoint(
                        jwksClient, JWKS_PATH, Map.of(), KeyRotationPolicy.defaults(),
                        Clock.systemUTC(), issuer, AUDIENCE);

                try (LoanedBuffer token = TestJwt.bufferOf(accessToken)) {
                    assertThat(provider.canAttempt(token)).as("routing peek matches own issuer").isTrue();
                }

                try (LoanedBuffer token = TestJwt.bufferOf(accessToken)) {
                    AuthenticationResult result = provider.authenticate(token);
                    assertThat(result.principal().principalId()).isEqualTo(UUID.fromString(SUBJECT_ID));
                    assertThat(result.principal().hasScope("security:read")).isTrue();
                    assertThat(result.storage().strategy()).as("isolation strategy").isNotNull();
                }

                // Fail-closed: same signature-valid token, foreign-issuer provider → terminal deny.
                CommunityOidcIdentityProvider foreign = CommunityOidcIdentityProvider.overJwksEndpoint(
                        jwksClient, JWKS_PATH, Map.of(), KeyRotationPolicy.defaults(),
                        Clock.systemUTC(), FOREIGN_ISSUER, AUDIENCE);
                try (LoanedBuffer token = TestJwt.bufferOf(accessToken)) {
                    assertThatThrownBy(() -> foreign.authenticate(token))
                            .isInstanceOf(SecurityAuthenticationException.class)
                            .extracting(e -> ((SecurityAuthenticationException) e).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_SEC_2002);
                }
            }
        });
    }

    private String mintAccessToken() throws Exception {
        String form = "grant_type=password"
                + "&client_id=" + CLIENT_ID
                + "&username=" + USERNAME
                + "&password=" + PASSWORD;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("token endpoint status").isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
        String accessToken = (String) body.get("access_token");
        assertThat(accessToken).as("access_token present").isNotBlank();
        return accessToken;
    }

    private static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KC_PORT);
    }

    private static HttpConfig clientConfig() {
        return new HttpConfig(
                HttpMode.CLIENT,
                KEYCLOAK.getHost(),
                KEYCLOAK.getMappedPort(KC_PORT),
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1
        );
    }
}
