/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reachability half of {@code ClaimsMapper} — the SPI calls itself "the <b>only</b>
 * application-customisable mapping point in the identity pipeline", and until v0.11 a booted kernel
 * could not reach it (ADR-063, cross-repo stub).
 *
 * <p>The decision is tested through {@code select(Iterable)} rather than through the classpath: a
 * service file in test resources would apply to every test in this module and make the
 * "nothing registered" case unreachable, so the lookup and the decision are separated in the
 * resolver for exactly that reason.
 */
@DisplayName("CommunityClaimsMapperResolver — the ClaimsMapper seam is reachable (ADR-063)")
class CommunityClaimsMapperResolverTest {

    @Test
    @DisplayName("with no ClaimsMapper registered, the Community default is used")
    void defaultsWhenNoneRegistered() {
        ClaimsMapper resolved = CommunityClaimsMapperResolver.resolve();

        assertThat(resolved)
                .as("an unconfigured deployment must keep the behaviour it had before the seam opened")
                .isInstanceOf(CommunityClaimsMapper.class);
    }

    @Test
    @DisplayName("a single registered mapper wins over the Community default")
    void registeredMapperWins() {
        ClaimsMapper custom = claims -> null;

        assertThat(CommunityClaimsMapperResolver.select(List.of(custom)))
                .as("this is the reachability the SPI contract promised and did not deliver")
                .isSameAs(custom);
    }

    @Test
    @DisplayName("two registered mappers are refused, not silently ordered")
    void twoMappersAreRefused() {
        ClaimsMapper first = claims -> null;
        ClaimsMapper second = claims -> null;

        assertThatThrownBy(() -> CommunityClaimsMapperResolver.select(List.of(first, second)))
                .as("ClaimsMapper has no priority(), so classpath order must not decide how every "
                        + "principal in the deployment is identified")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous ClaimsMapper")
                .hasMessageContaining("register exactly one");
    }

    /** A loader that reports exactly one {@link ClaimsMapper} service, without touching test resources. */
    private static ClassLoader loaderRegistering(Class<? extends ClaimsMapper> impl) {
        String entry = "META-INF/services/" + ClaimsMapper.class.getName();
        byte[] body = (impl.getName() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ClassLoader(CommunityClaimsMapperResolverTest.class.getClassLoader()) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name)
                    throws java.io.IOException {
                if (entry.equals(name)) {
                    java.net.URL url = java.net.URL.of(
                            java.net.URI.create("string:///" + entry),
                            new java.net.URLStreamHandler() {
                                @Override
                                protected java.net.URLConnection openConnection(java.net.URL u) {
                                    return new java.net.URLConnection(u) {
                                        @Override public void connect() { }
                                        @Override public java.io.InputStream getInputStream() {
                                            return new java.io.ByteArrayInputStream(body);
                                        }
                                    };
                                }
                            });
                    return java.util.Collections.enumeration(java.util.List.of(url));
                }
                return super.getResources(name);
            }
        };
    }

    /** Test-only mapper, registered through {@link #loaderRegistering} rather than test resources. */
    public static final class RegisteredMapper implements ClaimsMapper {
        @Override
        public eu.exeris.kernel.spi.security.PrincipalContext map(
                eu.exeris.kernel.spi.security.identity.VerifiedClaims claims) {
            throw new UnsupportedOperationException("not invoked by this test");
        }
    }

    @Test
    @DisplayName("a mapper registered on the context classpath reaches the booted provider")
    void registeredMapperReachesTheProvider() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loaderRegistering(RegisteredMapper.class));
        try {
            CommunitySecurityProvider provider = new CommunitySecurityProvider();

            assertThat(provider.identityProvider())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(CommunityOidcIdentityProvider.class))
                    .satisfies(oidc -> assertThat(oidc.claimsMapper())
                            .as("this is the whole point of the change: without the resolver wired "
                                    + "into CommunitySecurityProvider a registered mapper is ignored")
                            .isInstanceOf(RegisteredMapper.class));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * The wiring itself. An earlier version of this class asserted only that the provider assembled,
     * and a mutation run — reverting {@code CommunitySecurityProvider} to build the identity provider
     * without the resolver — left every case green. That is the change this PR exists to make, so it
     * needs an assertion that fails when it is undone.
     */
    @Test
    @DisplayName("the ServiceLoader boot path assembles the provider through the resolver")
    void bootPathGoesThroughTheResolver() {
        CommunitySecurityProvider provider = new CommunitySecurityProvider();

        assertThat(provider.identityProvider())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(CommunityOidcIdentityProvider.class))
                .satisfies(oidc -> assertThat(oidc.claimsMapper())
                        .as("the mapper must come from CommunityClaimsMapperResolver, not from the "
                                + "constructor's own default — reverting the wiring must redden this")
                        .isNotNull()
                        .isInstanceOf(CommunityClaimsMapper.class));
    }
}
