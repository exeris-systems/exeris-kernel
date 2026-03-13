/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@code SecurityInterceptor} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Successful authentication binds {@link KernelProviders#PRINCIPAL_CONTEXT}
 *       and {@link KernelProviders#STORAGE_CONTEXT} atomically for the request scope</li>
 *   <li>Both contexts are unbound after the handler returns</li>
 *   <li>Failed authentication drops the request without invoking the handler
 *       (Fail-Closed Gate)</li>
 *   <li>{@link KernelProviders#PRINCIPAL_CONTEXT} and {@link KernelProviders#STORAGE_CONTEXT}
 *       propagate to all child Virtual Threads via {@code StructuredTaskScope}</li>
 *   <li>Concurrent requests do not cross-contaminate each other's contexts</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunitySecurityInterceptorTckTest extends AbstractSecurityInterceptorTck {
 *
 *     @Override
 *     protected SecurityInterceptor createInterceptor(SecurityProvider provider) {
 *         return new SecurityInterceptor(provider);
 *     }
 *
 *     @Override
 *     protected SecurityProvider createSuccessProvider(PrincipalContext principal,
 *                                                       StorageContext storage) {
 *         return new StubSecurityProvider(principal, storage);
 *     }
 *
 *     @Override
 *     protected SecurityProvider createFailProvider() {
 *         return new AlwaysFailSecurityProvider();
 *     }
 *
 *     @Override
 *     protected LoanedBuffer createTokenBuffer() {
 *         return TestTokens.anyValidBuffer();
 *     }
 *
 *     @Override
 *     protected PrincipalContext createTestPrincipal() {
 *         return ImmutablePrincipal.ofTenant(UUID.randomUUID(), UUID.randomUUID(),
 *                 Set.of("ROLE_USER"));
 *     }
 * }
 * }</pre>
 *
 * @param <I> the concrete interceptor type under test
 * @since 0.5.0
 */
public abstract class AbstractSecurityInterceptorTck<I> {

    // =========================================================================
    // Template methods — subclass supplies the SUT
    // =========================================================================

    /**
     * Creates the interceptor under test backed by the given provider.
     *
     * @param provider the security provider to back the interceptor
     * @return configured interceptor instance
     */
    protected abstract I createInterceptor(SecurityProvider provider);

    /**
     * Creates a {@link SecurityProvider} that always succeeds authentication,
     * returning an {@link AuthenticationResult} built from the given principal and storage.
     *
     * @param principal the principal the provider should return
     * @param storage   the storage context the provider should return
     * @return success provider
     */
    protected abstract SecurityProvider createSuccessProvider(PrincipalContext principal,
                                                               StorageContext storage);

    /**
     * Creates a {@link SecurityProvider} that always fails authentication
     * by throwing {@link SecurityAuthenticationException}.
     *
     * @return fail provider
     */
    protected abstract SecurityProvider createFailProvider();

    /**
     * Creates a token buffer suitable for the above providers.
     * Caller retains ownership.
     *
     * @return token buffer
     */
    protected abstract LoanedBuffer createTokenBuffer();

    /**
     * Creates a test {@link PrincipalContext} for use in assertions.
     *
     * @return test principal
     */
    protected abstract PrincipalContext createTestPrincipal();

    /**
     * Invokes the interceptor with the given token and handler.
     *
     * <p>The implementation must call the equivalent of:
     * <pre>{@code interceptor.intercept(token, handler)</pre>
     *
     * @param interceptor   the interceptor instance
     * @param token         the token buffer
     * @param handler       the request handler
     * @return {@code true} if the handler was invoked; {@code false} if dropped
     */
    protected abstract boolean intercept(I interceptor, LoanedBuffer token, Runnable handler);

    // =========================================================================
    // Test fixtures
    // =========================================================================

    private PrincipalContext testPrincipal;
    private StorageContext testStorage;
    private I successInterceptor;
    private I failInterceptor;

    @BeforeEach
    final void setUpFixtures() {
        testPrincipal = createTestPrincipal();
        testStorage = ImmutableStorageContext.GLOBAL;
        successInterceptor = createInterceptor(createSuccessProvider(testPrincipal, testStorage));
        failInterceptor = createInterceptor(createFailProvider());
    }

    // =========================================================================
    // Successful authentication
    // =========================================================================

    @Nested
    @DisplayName("Successful authentication contract")
    class SuccessfulAuthentication {

        @Test
        @DisplayName("returns true when handler is invoked")
        void returnsTrueOnSuccess() {
            try (LoanedBuffer token = createTokenBuffer()) {
                boolean result = intercept(successInterceptor, token, () -> {});
                assertThat(result).isTrue();
            }
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is bound and correct within the handler scope")
        void principalContextBound() {
            AtomicReference<PrincipalContext> captured = new AtomicReference<>();
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token,
                        () -> captured.set(KernelProviders.PRINCIPAL_CONTEXT.get()));
            }
            assertThat(captured.get()).isEqualTo(testPrincipal);
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is bound within the handler scope")
        void storageContextBound() {
            AtomicBoolean bound = new AtomicBoolean(false);
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token,
                        () -> bound.set(KernelProviders.STORAGE_CONTEXT.isBound()));
            }
            assertThat(bound.get()).isTrue();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is unbound after handler returns")
        void principalContextUnboundAfterHandler() {
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token, () -> {});
            }
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is unbound after handler returns")
        void storageContextUnboundAfterHandler() {
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token, () -> {});
            }
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }
    }

    // =========================================================================
    // Fail-Closed Gate
    // =========================================================================

    @Nested
    @DisplayName("Fail-Closed Gate — authentication failure")
    class FailClosedGate {

        @Test
        @DisplayName("returns false when authentication fails")
        void returnsFalseOnFailure() {
            AtomicBoolean handlerInvoked = new AtomicBoolean(false);
            try (LoanedBuffer token = createTokenBuffer()) {
                boolean result = intercept(failInterceptor, token,
                        () -> handlerInvoked.set(true));
                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("handler is never invoked on authentication failure")
        void handlerNeverInvoked() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(failInterceptor, token, () -> invoked.set(true));
            }
            assertThat(invoked.get()).isFalse();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT remains unbound after gate drop")
        void principalContextUnboundAfterDrop() {
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(failInterceptor, token, () -> {});
            }
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
        }
    }

    // =========================================================================
    // Virtual Thread Propagation (JEP 506)
    // =========================================================================

    @Nested
    @DisplayName("ScopedValue propagation to child Virtual Threads")
    class ScopedValuePropagation {

        @Test
        @DisplayName("50 child VTs all see the correct PRINCIPAL_CONTEXT via StructuredTaskScope")
        void childVirtualThreadsInheritPrincipalContext() throws InterruptedException {
            int vtCount = 50;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token, () -> {
                    try (var scope = StructuredTaskScope.open()) {
                        for (int i = 0; i < vtCount; i++) {
                            scope.fork(() -> {
                                try {
                                    PrincipalContext p = KernelProviders.principal();
                                    if (p.equals(testPrincipal)) {
                                        successCount.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    failure.compareAndSet(null, e);
                                }
                                return null;
                            });
                        }
                        scope.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted", e);
                    }
                });
            }

            assertThat(failure.get()).isNull();
            assertThat(successCount.get()).isEqualTo(vtCount);
        }
    }
}

