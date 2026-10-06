/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.http.CommunityEndpointScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityJwksEndpoint — reading the JWKS URI")
class CommunityJwksEndpointTest {

    @Test
    @DisplayName("https with no port is reached on 443, and the query stays on the request target")
    void httpsDefaultsTo443() {
        CommunityJwksEndpoint endpoint = CommunityJwksEndpoint.of(URI.create("https://IdP.Example.com./jwks?v=2"));

        assertThat(endpoint.scheme()).isEqualTo(CommunityEndpointScheme.HTTPS);
        assertThat(endpoint.host()).as("lower-cased, trailing dot removed").isEqualTo("idp.example.com");
        assertThat(endpoint.port()).isEqualTo(443);
        assertThat(endpoint.requestTarget()).isEqualTo("/jwks?v=2");
        assertThat(endpoint.dialAuthority()).isEqualTo("idp.example.com:443");
    }

    @Test
    @DisplayName("http with an explicit port keeps it")
    void httpKeepsAnExplicitPort() {
        CommunityJwksEndpoint endpoint = CommunityJwksEndpoint.of(URI.create("HTTP://keycloak:8080/realms/x/certs"));

        assertThat(endpoint.scheme()).isEqualTo(CommunityEndpointScheme.HTTP);
        assertThat(endpoint.dialAuthority()).isEqualTo("keycloak:8080");
        assertThat(endpoint.requestTarget()).isEqualTo("/realms/x/certs");
    }

    @Test
    @DisplayName("an empty path requests the root")
    void emptyPathIsTheRoot() {
        assertThat(CommunityJwksEndpoint.of(URI.create("https://idp.example.com")).requestTarget()).isEqualTo("/");
    }

    @Test
    @DisplayName("an IPv6 literal keeps its brackets in the dialled authority")
    void ipv6KeepsBrackets() {
        assertThat(CommunityJwksEndpoint.of(URI.create("https://[::1]:8443/jwks")).dialAuthority())
                .isEqualTo("[::1]:8443");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://idp.example.com/jwks", "/relative/jwks", "idp.example.com/jwks"})
    @DisplayName("a scheme other than http or https, or none, is refused")
    void otherSchemesAreRefused(String uri) {
        assertThatThrownBy(() -> CommunityJwksEndpoint.of(URI.create(uri)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwksUri must use the http or https scheme");
    }

    @Test
    @DisplayName("user information, a fragment, or no host is refused")
    void unusableShapesAreRefused() {
        assertThatThrownBy(() -> CommunityJwksEndpoint.of(URI.create("https://user:pw@idp.example.com/jwks")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user information");
        assertThatThrownBy(() -> CommunityJwksEndpoint.of(URI.create("https://idp.example.com/jwks#k")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fragment");
        assertThatThrownBy(() -> CommunityJwksEndpoint.of(URI.create("https:///jwks")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");
    }
}
