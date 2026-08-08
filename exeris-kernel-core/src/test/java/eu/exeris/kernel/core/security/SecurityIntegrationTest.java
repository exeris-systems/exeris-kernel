/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Security subsystem:
 * {@link SecurityInterceptor} + {@link CitadelGuard} + {@link StorageContextBridge}.
 *
 * <p>Verifies end-to-end flows across Virtual Thread boundaries using
 * {@link StructuredTaskScope} — the only permitted concurrency primitive.
 */
@DisplayName("Security subsystem — integration tests")
class SecurityIntegrationTest {

    private static final UUID ADMIN_ID    = UUID.randomUUID();
    private static final UUID TENANT_ID   = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();

    private static final ImmutablePrincipal ADMIN_PRINCIPAL =
            ImmutablePrincipal.ofTenant(ADMIN_ID, TENANT_ID, Set.of("ROLE_ADMIN", "ROLE_USER"));

    private static final ImmutablePrincipal USER_PRINCIPAL =
            ImmutablePrincipal.ofTenant(USER_ID, TENANT_ID, Set.of("ROLE_USER"));

    private static final ImmutableStorageContext TENANT_STORAGE =
            ImmutableStorageContext.shared(TENANT_ID.toString());

    // =========================================================================
    // Stubs
    // =========================================================================

    private static final class StubBuffer implements LoanedBuffer {
        @Override public MemorySegment segment() { return MemorySegment.NULL; }
        @Override public long size() { return 0L; }
        @Override public long capacity() { return 0L; }
        @Override public void setSize(long newSize) { /* no-op: stub buffer has no backing storage */ }
        @Override public boolean isAlive() { return true; }
        @Override public void close() { /* no-op: stub buffer holds no native resources */ }
        @Override public void retain() { /* no-op: stub buffer uses trivial ref-counting */ }
        @Override public int refCount() { return 1; }
        @Override public void addCloseAction(Runnable action) { /* no-op: stub buffer ignores close hooks */ }
        @Override public LoanedBuffer slice(long offset, long length) { return this; }
        @Override public LoanedBuffer view() { return this; }
        @Override public LoanedBuffer peek(long offset, long length) { return this; }
    }

    private record TenantProvider(ImmutablePrincipal principal) implements SecurityProvider {
        @Override public String providerId() { return "tenant-stub"; }
        @Override public String providerName() { return "TenantStub"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) {
            return new AuthenticationResult(principal, TENANT_STORAGE);
        }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
    }

    private static final class InvalidTokenProvider implements SecurityProvider {
        @Override public String providerId() { return "invalid-stub"; }
        @Override public String providerName() { return "InvalidStub"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) {
            throw new SecurityAuthenticationException("JWT", "expired");
        }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
    }

    // =========================================================================
    // Full pipeline: SecurityInterceptor → CitadelGuard → StorageContextBridge
    // =========================================================================

    @Nested
    @DisplayName("Full pipeline: intercept → RBAC → bridge derivation")
    class FullPipeline {

        @Test
        @DisplayName("admin can access protected resource — full pipeline succeeds")
        void adminCanAccessResource() {
            SecurityInterceptor interceptor = new SecurityInterceptor(new TenantProvider(ADMIN_PRINCIPAL));
            CitadelGuard guard = new CitadelGuard();
            guard.preAllocate("ROLE_ADMIN");

            AtomicReference<String> capturedIsolationKey = new AtomicReference<>();

            interceptor.intercept(new StubBuffer(), () -> {
                guard.requireRole("ROLE_ADMIN");
                StorageContext derived = StorageContextBridge.deriveFromActivePrincipal();
                capturedIsolationKey.set(derived.isolationKey().orElse("MISSING"));
            });

            assertThat(capturedIsolationKey.get()).isEqualTo(TENANT_ID.toString());
        }

        @Test
        @DisplayName("user lacks ROLE_ADMIN — CitadelGuard rejects inside interceptor scope")
        void userCannotAccessAdminResource() {
            SecurityInterceptor interceptor = new SecurityInterceptor(new TenantProvider(USER_PRINCIPAL));
            CitadelGuard guard = new CitadelGuard();
            guard.preAllocate("ROLE_ADMIN");
            Runnable protectedCall = () -> interceptor.intercept(
                    new StubBuffer(), () -> guard.requireRole("ROLE_ADMIN"));
            assertThatThrownBy(protectedCall::run)
                    .isInstanceOf(InsufficientPrivilegesException.class);
        }

        @Test
        @DisplayName("invalid token — fail-closed gate drops request, CitadelGuard never reached")
        void invalidTokenDropsRequest() {
            SecurityInterceptor interceptor = new SecurityInterceptor(new InvalidTokenProvider());
            AtomicInteger guardCallCount = new AtomicInteger(0);

            boolean result = interceptor.intercept(new StubBuffer(), guardCallCount::incrementAndGet);

            assertThat(result).isFalse();
            assertThat(guardCallCount.get()).isZero();
        }
    }

    // =========================================================================
    // Virtual Thread inheritance — StructuredTaskScope
    // =========================================================================

    @Nested
    @DisplayName("Virtual Thread inheritance via StructuredTaskScope")
    class VirtualThreadInheritance {

        @Test
        @DisplayName("PRINCIPAL_CONTEXT and STORAGE_CONTEXT propagate to child VTs inside StructuredTaskScope")
        void contextsInheritedByChildVirtualThreads() {
            SecurityInterceptor interceptor =
                    new SecurityInterceptor(new TenantProvider(ADMIN_PRINCIPAL));

            int vtCount = 50;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            interceptor.intercept(new StubBuffer(), () -> {
                try (var scope = StructuredTaskScope.open()) {
                    for (int i = 0; i < vtCount; i++) {
                        scope.fork(() -> {
                            try {
                                PrincipalContext p = KernelProviders.principal();
                                StorageContext   s = KernelProviders.storageContext();

                                if (p.principalId().equals(ADMIN_ID)
                                        && s.strategy() == IsolationStrategy.SHARED
                                        && s.isolationKey().filter(k -> k.equals(TENANT_ID.toString())).isPresent()) {
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
                    throw new AssertionError("StructuredTaskScope interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    // JDK 28: a failed subtask arrives here as a checked exception. The bodies
                    // capture their own failures into `failure`, so this means the scope gave up.
                    throw new AssertionError("subtask failed", e);
                }
            });

            assertThat(failure.get())
                    .as("No child VT should throw: %s", failure.get())
                    .isNull();
            assertThat(successCount.get())
                    .as("All %d child VTs must see the correct context", vtCount)
                    .isEqualTo(vtCount);
        }

        @Test
        @DisplayName("Concurrent 100-VT authentication — no data corruption, no context cross-contamination")
        @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
        void concurrentAuthenticationIsolation() throws InterruptedException, java.util.concurrent.ExecutionException {
            CitadelGuard guard = new CitadelGuard();
            guard.preAllocate("ROLE_ADMIN");

            int requestCount = 100;
            AtomicInteger adminSuccessCount = new AtomicInteger(0);
            AtomicInteger userRejectedCount = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            try (var scope = StructuredTaskScope.open()) {
                for (int i = 0; i < requestCount; i++) {
                    boolean isAdmin = (i % 2 == 0);
                    ImmutablePrincipal principal = isAdmin ? ADMIN_PRINCIPAL : USER_PRINCIPAL;
                    SecurityInterceptor interceptor =
                            new SecurityInterceptor(new TenantProvider(principal));

                    scope.fork(() -> {
                        interceptor.intercept(new StubBuffer(), () -> {
                            try {
                                guard.requireRole("ROLE_ADMIN");
                                if (KernelProviders.principal().principalId().equals(ADMIN_ID)) {
                                    adminSuccessCount.incrementAndGet();
                                }
                            } catch (InsufficientPrivilegesException _) {
                                if (KernelProviders.principal().principalId().equals(USER_ID)) {
                                    userRejectedCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                failure.compareAndSet(null, e);
                            }
                        });
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(failure.get()).isNull();
            assertThat(adminSuccessCount.get()).isEqualTo(requestCount / 2);
            assertThat(userRejectedCount.get()).isEqualTo(requestCount / 2);
        }
    }

    // =========================================================================
    // Isolation Bridge: StorageContext derives from active PrincipalContext
    // =========================================================================

    @Nested
    @DisplayName("Isolation Bridge: StorageContextBridge derives from PrincipalContext")
    class IsolationBridgeIntegration {

        @Test
        @DisplayName("STORAGE_CONTEXT bound by interceptor matches bridge derivation for SHARED strategy")
        void boundStorageMatchesBridgeDerivation() {
            SecurityInterceptor interceptor =
                    new SecurityInterceptor(new TenantProvider(ADMIN_PRINCIPAL));

            AtomicReference<Boolean> match = new AtomicReference<>(false);
            interceptor.intercept(new StubBuffer(), () -> {
                StorageContext bound   = KernelProviders.storageContext();
                StorageContext derived = StorageContextBridge.deriveFromActivePrincipal();

                match.set(
                        bound.strategy() == derived.strategy()
                        && bound.isolationKey().equals(derived.isolationKey())
                );
            });

            assertThat(match.get()).isTrue();
        }
    }
}


