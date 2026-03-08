/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link SecurityProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerId()} returns a non-null, non-blank stable identifier</li>
 *   <li>{@code providerName()} returns a non-null, non-blank display name</li>
 *   <li>{@code priority()} follows the Open-Core convention (0 = Community, 100 = Enterprise)</li>
 *   <li>{@code authenticate(LoanedBuffer)} returns a fully-populated {@link AuthenticationResult}</li>
 *   <li>{@code authenticate()} with invalid token throws {@link SecurityAuthenticationException}</li>
 *   <li>{@code systemStorageContext()} returns a valid, non-null context</li>
 *   <li>ServiceLoader discovery selects the highest-priority provider</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunitySecurityProviderTckTest extends AbstractSecurityProviderTck {
 *     @Override protected SecurityProvider createProvider() {
 *         return new CommunitySecurityProvider(jwtKeySupplier);
 *     }
 *     @Override protected LoanedBuffer createValidTokenBuffer() {
 *         return TestTokens.validJwt(allocator);
 *     }
 *     @Override protected LoanedBuffer createInvalidTokenBuffer() {
 *         return TestTokens.corruptJwt(allocator);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractSecurityProviderTck {

    // =========================================================================
    // Template methods — subclass supplies the SUT
    // =========================================================================

    /** Creates the {@link SecurityProvider} under test. */
    protected abstract SecurityProvider createProvider();

    /**
     * Creates a {@link LoanedBuffer} containing a valid, parseable token.
     * The token MUST be decodable by the provider returned from {@link #createProvider()}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createValidTokenBuffer();

    /**
     * Creates a {@link LoanedBuffer} containing an invalid / corrupt token.
     * The provider MUST reject this token by throwing {@link SecurityAuthenticationException}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createInvalidTokenBuffer();

    private SecurityProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    // =========================================================================
    // Provider Identity Contract
    // =========================================================================

    @Nested
    @DisplayName("Provider identity contract")
    class IdentityContract {

        @Test
        @DisplayName("providerId() is non-null and non-blank")
        void providerIdIsNonBlank() {
            assertThat(provider.providerId())
                    .as("providerId must be a stable, non-blank identifier")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("providerName() is non-null and non-blank")
        void providerNameIsNonBlank() {
            assertThat(provider.providerName())
                    .as("providerName must be a human-readable display name")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("providerId() is stable across calls (no per-call allocation)")
        void providerIdIsStable() {
            String first = provider.providerId();
            String second = provider.providerId();
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("providerName() is stable across calls")
        void providerNameIsStable() {
            String first = provider.providerName();
            String second = provider.providerName();
            assertThat(first).isEqualTo(second);
        }
    }

    // =========================================================================
    // Open-Core Priority Convention
    // =========================================================================

    @Nested
    @DisplayName("Open-Core priority convention")
    class PriorityConvention {

        @Test
        @DisplayName("priority() is non-negative")
        void priorityIsNonNegative() {
            assertThat(provider.priority())
                    .as("priority must be >= 0")
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Community returns 0, Enterprise returns 100")
        void priorityFollowsConvention() {
            assertThat(provider.priority())
                    .as("Open-Core convention: Community = 0, Enterprise = 100")
                    .isIn(0, 100);
        }
    }

    // =========================================================================
    // authenticate() — happy path
    // =========================================================================

    @Nested
    @DisplayName("authenticate() — valid token")
    class AuthenticateHappyPath {

        @Test
        @DisplayName("returns non-null AuthenticationResult")
        void returnsNonNullResult() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                AuthenticationResult result = provider.authenticate(token);
                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("result contains non-null PrincipalContext")
        void resultHasPrincipal() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                AuthenticationResult result = provider.authenticate(token);
                PrincipalContext p = result.principal();
                assertThat(p).isNotNull();
                assertThat(p.principalId()).as("principalId must not be null").isNotNull();
            }
        }

        @Test
        @DisplayName("principal has immutable, non-null roles set")
        void principalRolesImmutable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext p = provider.authenticate(token).principal();
                var roles = p.roles();
                assertThat(roles).isNotNull();
                assertThatThrownBy(() -> roles.add("ROLE_ROGUE"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("principal has immutable, non-null scopes set")
        void principalScopesImmutable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext p = provider.authenticate(token).principal();
                var scopes = p.scopes();
                assertThat(scopes).isNotNull();
                assertThatThrownBy(() -> scopes.add("admin:nuke"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("result contains non-null StorageContext with valid strategy")
        void resultHasStorage() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                StorageContext s = provider.authenticate(token).storage();
                assertThat(s).isNotNull();
                assertThat(s.strategy()).isNotNull();
                assertThat(s.attributes()).isNotNull();
            }
        }

        @Test
        @DisplayName("StorageContext attributes are unmodifiable")
        void storageAttributesUnmodifiable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                StorageContext s = provider.authenticate(token).storage();
                var attributes = s.attributes();
                assertThatThrownBy(() -> attributes.put("rogue", "val"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    // =========================================================================
    // authenticate() — failure path
    // =========================================================================

    @Nested
    @DisplayName("authenticate() — invalid token")
    class AuthenticateFailurePath {

        @Test
        @DisplayName("throws SecurityAuthenticationException for invalid token")
        void throwsOnInvalidToken() {
            try (LoanedBuffer token = createInvalidTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOf(SecurityAuthenticationException.class);
            }
        }
    }

    // =========================================================================
    // systemStorageContext()
    // =========================================================================

    @Nested
    @DisplayName("systemStorageContext() contract")
    class SystemStorageContract {

        @Test
        @DisplayName("returns non-null context")
        void systemContextNonNull() {
            StorageContext ctx = provider.systemStorageContext();
            assertThat(ctx).isNotNull();
        }

        @Test
        @DisplayName("has non-null strategy")
        void systemContextHasStrategy() {
            StorageContext ctx = provider.systemStorageContext();
            assertThat(ctx.strategy()).isNotNull();
        }

        @Test
        @DisplayName("attributes are non-null and unmodifiable")
        void systemContextAttributesSafe() {
            StorageContext ctx = provider.systemStorageContext();
            var attributes = ctx.attributes();
            assertThat(attributes).isNotNull();
            assertThatThrownBy(() -> attributes.put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("is stable — returns equivalent context on repeated calls")
        void systemContextIsStable() {
            StorageContext a = provider.systemStorageContext();
            StorageContext b = provider.systemStorageContext();
            assertThat(a.strategy()).isEqualTo(b.strategy());
            assertThat(a.isolationKey()).isEqualTo(b.isolationKey());
        }
    }

    // =========================================================================
    // ServiceLoader integration
    // =========================================================================

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderIntegration {

        @Test
        @DisplayName("SecurityProvider is discoverable via ServiceLoader")
        void discoverableViaServiceLoader() {
            ServiceLoader<SecurityProvider> loader = ServiceLoader.load(SecurityProvider.class);
            assertThat(loader.stream().count())
                    .as("At least one SecurityProvider must be on the classpath")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins selection")
        void highestPriorityWins() {
            SecurityProvider selected = ServiceLoader.load(SecurityProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(SecurityProvider::priority))
                    .orElseThrow();
            // The selected provider must be the same tier as our test provider
            assertThat(selected.priority())
                    .isGreaterThanOrEqualTo(provider.priority());
        }
    }

    // =========================================================================
    // Thread safety — Virtual Thread concurrent authenticate()
    // =========================================================================

    @Nested
    @DisplayName("Thread safety — concurrent authenticate()")
    class ThreadSafety {

        @Test
        @DisplayName("100 concurrent VT authenticate() calls — no crashes, no data corruption")
        void concurrentAuthenticate() throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();

            try (var scope = StructuredTaskScope.open()) {
                for (int i = 0; i < 100; i++) {
                    scope.fork(() -> {
                        try (LoanedBuffer token = createValidTokenBuffer()) {
                            AuthenticationResult result = provider.authenticate(token);
                            if (result.principal().principalId() == null) {
                                failure.set(new AssertionError("principalId was null"));
                            }
                        } catch (Exception e) {
                            failure.compareAndSet(null, e);
                        }
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(failure.get())
                    .as("Concurrent authenticate() must be thread-safe")
                    .isNull();
        }
    }
}



