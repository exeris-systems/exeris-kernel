/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider bound at {@code initialize()} denies until {@code start()} builds what it delegates to,
 * and denies again once {@code close()} has released it.
 */
@DisplayName("DeferredSecurityProvider")
class DeferredSecurityProviderTest {

    private final CommunitySecurityProvider template = new CommunitySecurityProvider();
    private final AtomicInteger built = new AtomicInteger();

    private DeferredSecurityProvider deferred() {
        return new DeferredSecurityProvider(template, () -> {
            built.incrementAndGet();
            return new CommunitySecurityProvider(Map.of(TestJwt.TEST_KID, TestJwt.testPublicKey()),
                    TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
        });
    }

    @Test
    @DisplayName("reports the template's identity, priority and system context before start")
    void identityBeforeStart() {
        DeferredSecurityProvider provider = deferred();

        assertThat(provider.providerId()).isEqualTo(template.providerId());
        assertThat(provider.providerName()).isEqualTo(template.providerName());
        assertThat(provider.priority()).isEqualTo(template.priority());
        assertThat(provider.systemStorageContext()).isEqualTo(template.systemStorageContext());
        assertThat(built).as("nothing is built before start()").hasValue(0);
    }

    @Test
    @DisplayName("denies a valid token before start()")
    void deniesBeforeStart() {
        DeferredSecurityProvider provider = deferred();

        try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
            assertThatThrownBy(() -> provider.authenticate(token))
                    .isInstanceOf(SecurityAuthenticationException.class);
        }
    }

    @Test
    @DisplayName("admits a valid token after start(), through the built provider")
    void admitsAfterStart() {
        DeferredSecurityProvider provider = deferred();
        provider.start();

        try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
            assertThat(provider.authenticate(token).principal().principalId())
                    .isEqualTo(UUID.fromString(TestJwt.TEST_SUBJECT));
        }
        assertThat(built).hasValue(1);
    }

    @Test
    @DisplayName("denies a valid token after close(), and close() is idempotent")
    void deniesAfterClose() {
        DeferredSecurityProvider provider = deferred();
        provider.start();
        provider.close();
        provider.close();

        try (LoanedBuffer token = TestJwt.bufferOf(TestJwt.builder().serialize())) {
            assertThatThrownBy(() -> provider.authenticate(token))
                    .isInstanceOf(SecurityAuthenticationException.class);
        }
    }

    @Test
    @DisplayName("start() twice, or after close(), is refused")
    void startIsOneShot() {
        DeferredSecurityProvider started = deferred();
        started.start();
        assertThatThrownBy(started::start).isInstanceOf(IllegalStateException.class);

        DeferredSecurityProvider closed = deferred();
        closed.close();
        assertThatThrownBy(closed::start).isInstanceOf(IllegalStateException.class);
        assertThat(built).as("a refused start builds nothing").hasValue(1);
    }
}
