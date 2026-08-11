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

import java.util.ArrayList;
import java.util.List;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * returning an {@link eu.exeris.kernel.spi.security.AuthenticationResult} built from the given principal and storage.
     *
     * @param principal the principal the provider should return
     * @param storage   the storage context the provider should return
     * @return success provider
     */
    protected abstract SecurityProvider createSuccessProvider(PrincipalContext principal,
                                                               StorageContext storage);

    /**
     * Creates a {@link SecurityProvider} that always fails authentication
     * by throwing {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}.
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
     * <pre>{@code interceptor.intercept(token, handler)}</pre>
     *
     * @param interceptor   the interceptor instance
     * @param token         the token buffer
     * @param handler       the request handler
     * @return {@code true} if the handler was invoked; {@code false} if dropped
     */
    protected abstract boolean intercept(I interceptor, LoanedBuffer token, Runnable handler);

    /**
     * Invokes {@code interceptPreAuthenticated} on the interceptor under test.
     * Delegate to {@code interceptor.interceptPreAuthenticated(principal, handler)}.
     */
    protected abstract boolean interceptPreAuthenticated(I interceptor,
                                                          PrincipalContext principal,
                                                          Runnable handler);

    /**
     * Returns a {@link PrincipalContext} whose {@code tenantId()} call throws a
     * {@link RuntimeException}, exercising the bridge-failure fail-closed path.
     * All other methods must return non-throwing, valid values.
     */
    protected abstract PrincipalContext createBridgeFailurePrincipal();

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
            AtomicReference<StorageContext> captured = new AtomicReference<>();
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token,
                        () -> captured.set(KernelProviders.STORAGE_CONTEXT.get()));
            }
            assertThat(captured.get()).isEqualTo(testStorage);
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
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("provider uncertainty runtime failure is fail-closed")
        void providerUncertaintyRuntimeFailureIsFailClosed() {
            SecurityProvider uncertainProvider = new SecurityProvider() {
                @Override
                public String providerId() {
                    return "uncertain-provider";
                }

                @Override
                public String providerName() {
                    return "UncertainProvider";
                }

                @Override
                public int priority() {
                    return 0;
                }

                @Override
                public AuthenticationResult authenticate(LoanedBuffer token) {
                    throw new RuntimeException("indeterminate");
                }

                @Override
                public StorageContext systemStorageContext() {
                    return ImmutableStorageContext.GLOBAL;
                }
            };

            I uncertainInterceptor = createInterceptor(uncertainProvider);
            AtomicBoolean invoked = new AtomicBoolean(false);

            try (LoanedBuffer token = createTokenBuffer()) {
                boolean result = intercept(uncertainInterceptor, token, () -> invoked.set(true));
                assertThat(result).isFalse();
            }

            assertThat(invoked.get()).isFalse();
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }
    }

    // =========================================================================
    // Virtual Thread Propagation (JEP 506)
    // =========================================================================

    @Nested
    @DisplayName("ScopedValue propagation to child Virtual Threads")
    class ScopedValuePropagation {

        @Test
        @DisplayName("50 child VTs bound with the request's context all see it — the values, not the propagation")
        void boundChildVirtualThreadsSeePrincipalContext() {
            int vtCount = 50;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token, () -> {
                    // Captured on the request thread, rebound inside each child. Inheritance across a
                    // fork is a StructuredTaskScope property, not a kernel one, and the default line is
                    // preview-clean (ADR-066) — so what this pins is the kernel's part: the values the
                    // interceptor established are the ones a bound child observes, for all of them.
                    PrincipalContext bound = KernelProviders.principal();
                    StorageContext boundStorage = KernelProviders.STORAGE_CONTEXT.get();
                    try (TckScope scope = TckScope.open()) {
                        for (int i = 0; i < vtCount; i++) {
                            scope.fork(() -> {
                                ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, bound)
                                        .where(KernelProviders.STORAGE_CONTEXT, boundStorage)
                                        .run(() -> {
                                            try {
                                                PrincipalContext p = KernelProviders.principal();
                                                StorageContext st = KernelProviders.STORAGE_CONTEXT.get();
                                                if (p.equals(testPrincipal) && st.equals(testStorage)) {
                                                    successCount.incrementAndGet();
                                                }
                                            } catch (RuntimeException e) {
                                                failure.compareAndSet(null, e);
                                            }
                                        });
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

        @Test
        @DisplayName("the binding does not outlive intercept() — a ThreadLocal implementation would")
        void bindingIsUnboundAfterIntercept() {
            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token,
                        () -> assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound())
                                .as("inside the callback the interceptor's binding must be visible, "
                                        + "or everything below is asserting an empty scope")
                                .isTrue());
            }

            // The case above this one binds each child explicitly and then asserts the child sees
            // what it was given — which is a property of ScopedValue, not of any provider, and would
            // certify a kernel that propagated context through a ThreadLocal just as happily. THIS is
            // the assertion those cases were missing: a ThreadLocal set during the callback is still
            // set after it returns, and only a scoped binding is guaranteed gone.
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound())
                    .as("a request's identity must not survive the request. If it does, the next "
                            + "piece of work on this carrier thread inherits an authority nobody "
                            + "granted it — the failure mode ADR-012 rules out")
                    .isFalse();
        }

        @Test
        @DisplayName("an unstructured virtual thread inherits NOTHING — propagation is explicit")
        void plainVirtualThreadDoesNotInheritTheBinding() throws InterruptedException {
            AtomicBoolean childSawBinding = new AtomicBoolean(true);

            try (LoanedBuffer token = createTokenBuffer()) {
                intercept(successInterceptor, token, () -> {
                    assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound())
                            .as("the PARENT must hold the binding, or the child observing none "
                                    + "proves nothing about inheritance")
                            .isTrue();
                    // Not a fork inside a scope: a bare Thread.ofVirtual().start(). ScopedValue
                    // bindings are inherited across a structured fork and NOWHERE else, and the
                    // kernel has already shipped one claim to the contrary that measurement refuted.
                    // Pinning the negative keeps the next change from assuming the convenient answer.
                    Thread child = Thread.ofVirtual().start(
                            () -> childSawBinding.set(KernelProviders.PRINCIPAL_CONTEXT.isBound()));
                    try {
                        child.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted joining the child", e);
                    }
                });
            }

            assertThat(childSawBinding)
                    .as("a driver that hands work to a plain virtual thread must rebind explicitly; "
                            + "believing inheritance happens is how work runs with no identity at all")
                    .isFalse();
        }
    }

    // =========================================================================
    // Concurrent isolation contract
    // =========================================================================

    @Nested
    @DisplayName("Concurrent isolation contract")
    class ConcurrentIsolation {

        @Test
        @DisplayName("concurrent intercept() calls do not cross-contaminate PRINCIPAL_CONTEXT")
        void concurrentRequestsAreIsolated() throws Exception {
            int concurrency = 8;
            List<PrincipalContext> principals = new ArrayList<>(concurrency);
            List<AtomicReference<PrincipalContext>> captured = new ArrayList<>(concurrency);
            List<I> interceptorList = new ArrayList<>(concurrency);

            for (int i = 0; i < concurrency; i++) {
                PrincipalContext p = createTestPrincipal();
                principals.add(p);
                captured.add(new AtomicReference<>());
                StorageContext storage = ImmutableStorageContext.GLOBAL;
                interceptorList.add(createInterceptor(createSuccessProvider(p, storage)));
            }

            try (TckScope scope = TckScope.openFailFast()) {
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    scope.fork(() -> {
                        try (LoanedBuffer token = createTokenBuffer()) {
                            intercept(interceptorList.get(idx), token,
                                    () -> captured.get(idx).set(KernelProviders.PRINCIPAL_CONTEXT.get()));
                        }
                        return null;
                    });
                }
                scope.join();
            }

            for (int i = 0; i < concurrency; i++) {
                assertThat(captured.get(i).get())
                        .as("principal for slot %d must not be cross-contaminated", i)
                        .isEqualTo(principals.get(i));
            }
        }
    }

    // =========================================================================
    // Pre-Authenticated Bridge
    // =========================================================================

    @Nested
    @DisplayName("Pre-Authenticated Bridge contract")
    class PreAuthenticatedBridge {

        @Test
        @DisplayName("returns true on successful bridge derivation")
        void returnsTrueOnSuccess() {
            boolean result = interceptPreAuthenticated(successInterceptor, testPrincipal, () -> {});
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is bound and correct within the handler scope")
        void principalContextBound() {
            AtomicReference<PrincipalContext> captured = new AtomicReference<>();
            interceptPreAuthenticated(successInterceptor, testPrincipal,
                    () -> captured.set(KernelProviders.PRINCIPAL_CONTEXT.get()));
            assertThat(captured.get()).isEqualTo(testPrincipal);
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is bridge-derived from the principal")
        void storageContextIsBridgeDerived() {
            AtomicReference<StorageContext> captured = new AtomicReference<>();
            interceptPreAuthenticated(successInterceptor, testPrincipal,
                    () -> captured.set(KernelProviders.STORAGE_CONTEXT.get()));
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().strategy())
                    .isEqualTo(StorageContext.IsolationStrategy.SHARED);
        }

        @Test
        @DisplayName("both contexts are unbound after handler returns")
        void contextsUnboundAfterHandler() {
            interceptPreAuthenticated(successInterceptor, testPrincipal, () -> {});
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("null principal throws NullPointerException")
        void nullPrincipalThrowsNpe() {
            assertThatThrownBy(() -> interceptPreAuthenticated(successInterceptor, null, () -> {}))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null handler throws NullPointerException")
        void nullHandlerThrowsNpe() {
            assertThatThrownBy(() -> interceptPreAuthenticated(successInterceptor, testPrincipal, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("bridge failure drops request — handler not invoked, no context bound")
        void bridgeFailureDropsRequest() {
            PrincipalContext badPrincipal = createBridgeFailurePrincipal();
            AtomicBoolean invoked = new AtomicBoolean(false);

            boolean result = interceptPreAuthenticated(successInterceptor, badPrincipal, () -> invoked.set(true));

            assertThat(result).isFalse();
            assertThat(invoked.get()).isFalse();
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("a child virtual thread bound with the pre-authenticated principal observes it")
        void boundChildVirtualThreadsSeePrincipalContext() {
            int count = 10;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            interceptPreAuthenticated(successInterceptor, testPrincipal, () -> {
                // Same reformulation as the ScopedValuePropagation case: bound explicitly, because a
                // plain virtual thread does not inherit and the default line has no fork that does.
                PrincipalContext bound = KernelProviders.PRINCIPAL_CONTEXT.get();
                try (TckScope scope = TckScope.open()) {
                    for (int i = 0; i < count; i++) {
                        scope.fork(() -> {
                            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, bound).run(() -> {
                                try {
                                    PrincipalContext p = KernelProviders.PRINCIPAL_CONTEXT.get();
                                    if (p.equals(testPrincipal)) {
                                        successCount.incrementAndGet();
                                    }
                                } catch (RuntimeException e) {
                                    failure.compareAndSet(null, e);
                                }
                            });
                            return null;
                        });
                    }
                    scope.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", e);
                }
            });

            assertThat(failure.get()).isNull();
            assertThat(successCount.get()).isEqualTo(count);
        }
    }
}

