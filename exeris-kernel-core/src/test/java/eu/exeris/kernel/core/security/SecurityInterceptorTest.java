/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.exceptions.security.StorageContextMissingException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecurityInterceptor}.
 */
@DisplayName("SecurityInterceptor — unit tests")
class SecurityInterceptorTest {

    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID TENANT_ID    = UUID.randomUUID();

    private static final ImmutablePrincipal VALID_PRINCIPAL =
            ImmutablePrincipal.ofTenant(PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_USER"));

    private static final ImmutableStorageContext VALID_STORAGE =
            ImmutableStorageContext.shared(TENANT_ID.toString());

    private static final AuthenticationResult VALID_RESULT =
            new AuthenticationResult(VALID_PRINCIPAL, VALID_STORAGE);

    // =========================================================================
    // Stubs
    // =========================================================================

    private static final class StubLoanedBuffer implements LoanedBuffer {
        @Override public MemorySegment segment() { return MemorySegment.NULL; }
        @Override public long size() { return 0L; }
        @Override public long capacity() { return 0L; }
        @Override public void setSize(long newSize) { /* no-op: stub buffer has no backing storage */ }
        @Override public boolean isAlive() { return true; }
        @Override public void close() { /* no-op: stub buffer holds no native resources */ }
        @Override public void retain() { /* no-op: stub buffer uses trivial ref-counting */ }
        @Override public int refCount() { return 1; }
        @Override public void addCloseAction(Runnable action) { /* no-op: stub buffer ignores close hooks */ }
        @Override public eu.exeris.kernel.spi.memory.LoanedBuffer slice(long offset, long length) { return this; }
        @Override public eu.exeris.kernel.spi.memory.LoanedBuffer view() { return this; }
        @Override public eu.exeris.kernel.spi.memory.LoanedBuffer peek(long offset, long length) { return this; }
    }

    private static final class AlwaysSuccessProvider implements SecurityProvider {
        private final AuthenticationResult result;

        AlwaysSuccessProvider(AuthenticationResult result) {
            this.result = result;
        }

        @Override public String providerId() { return "stub-success"; }
        @Override public String providerName() { return "StubSuccess"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) { return result; }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
    }

    private static final class AlwaysFailProvider implements SecurityProvider {
        @Override public String providerId() { return "stub-fail"; }
        @Override public String providerName() { return "StubFail"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) {
            throw new SecurityAuthenticationException("STUB", "always-fails");
        }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
    }

    private static final class ThrowingTenantPrincipal implements PrincipalContext {
        private static final UUID STUB_ID = UUID.randomUUID();
        @Override public UUID principalId() { return STUB_ID; }
        @Override public Optional<UUID> tenantId() { throw new RuntimeException("bridge-test"); }
        @Override public Set<String> roles() { return Set.of(); }
        @Override public Set<String> scopes() { return Set.of(); }
    }

    private SecurityInterceptor interceptor;
    private StubLoanedBuffer    tokenBuffer;

    @BeforeEach
    void setUp() {
        interceptor = new SecurityInterceptor(new AlwaysSuccessProvider(VALID_RESULT));
        tokenBuffer = new StubLoanedBuffer();
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorContract {

        @Test
        @DisplayName("rejects null provider")
        void rejectsNullProvider() {
            assertThatThrownBy(() -> new SecurityInterceptor(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("provider() returns the configured provider")
        void providerIsReturned() {
            SecurityProvider p = new AlwaysSuccessProvider(VALID_RESULT);
            assertThat(new SecurityInterceptor(p).provider()).isSameAs(p);
        }
    }

    // =========================================================================
    // intercept() — happy path
    // =========================================================================

    @Nested
    @DisplayName("intercept() — successful authentication")
    class InterceptHappyPath {

        @Test
        @DisplayName("returns true when handler is invoked")
        void returnsTrueOnSuccess() {
            boolean result = interceptor.intercept(tokenBuffer, () -> {});
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is bound within the handler scope")
        void principalContextIsBound() {
            AtomicReference<PrincipalContext> captured = new AtomicReference<>();
            interceptor.intercept(tokenBuffer, () -> captured.set(KernelProviders.principal()));
            assertThat(captured.get()).isEqualTo(VALID_PRINCIPAL);
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is bound within the handler scope")
        void storageContextIsBound() {
            AtomicReference<StorageContext> captured = new AtomicReference<>();
            interceptor.intercept(tokenBuffer, () -> captured.set(KernelProviders.storageContext()));
            assertThat(captured.get()).isEqualTo(VALID_STORAGE);
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT and STORAGE_CONTEXT are bound atomically")
        void bothContextsBoundAtomically() {
            AtomicBoolean consistent = new AtomicBoolean(false);
            interceptor.intercept(tokenBuffer, () -> {
                PrincipalContext p = KernelProviders.PRINCIPAL_CONTEXT.get();
                StorageContext   s = KernelProviders.STORAGE_CONTEXT.get();
                consistent.set(p != null && s != null);
            });
            assertThat(consistent.get()).isTrue();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is unbound after handler returns")
        void principalContextUnboundAfterHandler() {
            interceptor.intercept(tokenBuffer, () -> {});
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is unbound after handler returns")
        void storageContextUnboundAfterHandler() {
            interceptor.intercept(tokenBuffer, () -> {});
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("handler receives the correct tenant isolation key")
        void handlerReceivesCorrectTenantKey() {
            AtomicReference<Optional<String>> key = new AtomicReference<>();
            interceptor.intercept(tokenBuffer, () ->
                    key.set(KernelProviders.STORAGE_CONTEXT.get().isolationKey())
            );
            assertThat(key.get()).isPresent().contains(TENANT_ID.toString());
        }
    }

    // =========================================================================
    // intercept() — fail-closed gate
    // =========================================================================

    @Nested
    @DisplayName("intercept() — fail-closed gate (authentication failure)")
    class FailClosedGate {

        @Test
        @DisplayName("returns false when provider throws SecurityAuthenticationException")
        void returnsFalseOnFailure() {
            SecurityInterceptor failing = new SecurityInterceptor(new AlwaysFailProvider());
            boolean result = failing.intercept(tokenBuffer, () -> {
                throw new AssertionError("handler must NOT be invoked");
            });
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("handler is never invoked when token is invalid")
        void handlerNotInvokedOnFailure() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            SecurityInterceptor failing = new SecurityInterceptor(new AlwaysFailProvider());
            failing.intercept(tokenBuffer, () -> invoked.set(true));
            assertThat(invoked.get()).isFalse();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT remains unbound after gate drop")
        void principalContextRemainsUnboundAfterDrop() {
            SecurityInterceptor failing = new SecurityInterceptor(new AlwaysFailProvider());
            failing.intercept(tokenBuffer, () -> {});
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
        }
    }

    // =========================================================================
    // runAsSystem()
    // =========================================================================

    @Nested
    @DisplayName("runAsSystem()")
    class RunAsSystem {

        @Test
        @DisplayName("binds supplied principal into PRINCIPAL_CONTEXT")
        void bindsPrincipalContext() {
            AtomicReference<PrincipalContext> captured = new AtomicReference<>();
            interceptor.runAsSystem(VALID_PRINCIPAL, () ->
                    captured.set(KernelProviders.PRINCIPAL_CONTEXT.get())
            );
            assertThat(captured.get()).isEqualTo(VALID_PRINCIPAL);
        }

        @Test
        @DisplayName("binds provider.systemStorageContext() into STORAGE_CONTEXT")
        void bindsSystemStorageContext() {
            AtomicReference<StorageContext> captured = new AtomicReference<>();
            interceptor.runAsSystem(VALID_PRINCIPAL, () ->
                    captured.set(KernelProviders.STORAGE_CONTEXT.get())
            );
            assertThat(captured.get()).isEqualTo(ImmutableStorageContext.GLOBAL);
        }

        @Test
        @DisplayName("always invokes task — no authentication performed")
        void alwaysInvokesTask() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            interceptor.runAsSystem(VALID_PRINCIPAL, () -> invoked.set(true));
            assertThat(invoked.get()).isTrue();
        }

        @Test
        @DisplayName("scope is torn down after task completes")
        void scopeTornDownAfterTask() {
            interceptor.runAsSystem(VALID_PRINCIPAL, () -> {});
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("rejects null systemPrincipal")
        void rejectsNullPrincipal() {
            assertThatThrownBy(() -> interceptor.runAsSystem(null, () -> {}))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null task")
        void rejectsNullTask() {
            assertThatThrownBy(() -> interceptor.runAsSystem(VALID_PRINCIPAL, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // interceptPreAuthenticated()
    // =========================================================================

    @Nested
    @DisplayName("interceptPreAuthenticated()")
    class InterceptPreAuthenticated {

        @Test
        @DisplayName("returns true when bridge succeeds")
        void returnsTrueWhenBridgeSucceeds() {
            boolean result = interceptor.interceptPreAuthenticated(VALID_PRINCIPAL, () -> {});
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT is bound and correct within the handler scope")
        void principalContextBoundWithinHandler() {
            AtomicReference<PrincipalContext> captured = new AtomicReference<>();
            interceptor.interceptPreAuthenticated(VALID_PRINCIPAL,
                    () -> captured.set(KernelProviders.principal()));
            assertThat(captured.get()).isEqualTo(VALID_PRINCIPAL);
        }

        @Test
        @DisplayName("STORAGE_CONTEXT is bridge-derived from the principal")
        void storageContextIsBridgeDerived() {
            AtomicReference<StorageContext> captured = new AtomicReference<>();
            interceptor.interceptPreAuthenticated(VALID_PRINCIPAL,
                    () -> captured.set(KernelProviders.STORAGE_CONTEXT.get()));
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().strategy()).isEqualTo(StorageContext.IsolationStrategy.SHARED);
        }

        @Test
        @DisplayName("both contexts are unbound after handler returns")
        void contextsUnboundAfterHandler() {
            interceptor.interceptPreAuthenticated(VALID_PRINCIPAL, () -> {});
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("null principal throws NullPointerException")
        void nullPrincipalThrowsNpe() {
            assertThatThrownBy(() -> interceptor.interceptPreAuthenticated(null, () -> {}))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null handler throws NullPointerException")
        void nullHandlerThrowsNpe() {
            assertThatThrownBy(() -> interceptor.interceptPreAuthenticated(VALID_PRINCIPAL, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("bridge failure returns false — handler not invoked, no context bound")
        void bridgeFailureReturnsFalse() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            boolean result = interceptor.interceptPreAuthenticated(
                    new ThrowingTenantPrincipal(), () -> invoked.set(true));
            assertThat(result).isFalse();
            assertThat(invoked.get()).isFalse();
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }
    }

    // =========================================================================
    // Scope isolation — contexts must not leak between calls
    // =========================================================================

    @Nested
    @DisplayName("Scope isolation — no context leaks between requests")
    class ScopeIsolation {

        @Test
        @DisplayName("PRINCIPAL_CONTEXT from first call is not visible in second call's pre-phase")
        void contextDoesNotLeakBetweenRequests() {
            interceptor.intercept(tokenBuffer, () -> {/* no-op: establishes then tears down scope */});

            assertThatThrownBy(KernelProviders::principal)
                    .isInstanceOf(PrincipalContextMissingException.class);
        }

        @Test
        @DisplayName("STORAGE_CONTEXT from first call is not visible in second call's pre-phase")
        void storageContextDoesNotLeakBetweenRequests() {
            interceptor.intercept(tokenBuffer, () -> {/* no-op: establishes then tears down scope */});

            assertThatThrownBy(KernelProviders::storageContext)
                    .isInstanceOf(StorageContextMissingException.class);
        }
    }
}



