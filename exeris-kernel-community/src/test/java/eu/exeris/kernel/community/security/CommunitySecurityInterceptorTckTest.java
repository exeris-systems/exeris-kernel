/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.security.AbstractSecurityInterceptorTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@DisplayName("Community SecurityInterceptor — pre-auth bridge TCK")
class CommunitySecurityInterceptorTckTest extends AbstractSecurityInterceptorTck<SecurityInterceptor> {

    // =========================================================================
    // Stubs
    // =========================================================================

    private static final class StubSuccessProvider implements SecurityProvider {
        private final PrincipalContext principal;
        private final StorageContext   storage;

        StubSuccessProvider(PrincipalContext principal, StorageContext storage) {
            this.principal = principal;
            this.storage   = storage;
        }

        @Override public String providerId() { return "stub-success"; }
        @Override public String providerName() { return "StubSuccess"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) {
            return new AuthenticationResult(principal, storage);
        }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
    }

    private static final class StubFailProvider implements SecurityProvider {
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

    private static final class NullLoanedBuffer implements LoanedBuffer {
        @Override public MemorySegment segment() { return MemorySegment.NULL; }
        @Override public long size() { return 0L; }
        @Override public long capacity() { return 0L; }
        @Override public void setSize(long newSize) { /* no-op: stub buffer holds no backing storage */ }
        @Override public boolean isAlive() { return true; }
        @Override public void close() { /* no-op: stub buffer holds no native resources */ }
        @Override public void retain() { /* no-op: stub buffer uses trivial ref-counting */ }
        @Override public int refCount() { return 1; }
        @Override public void addCloseAction(Runnable action) { /* no-op: stub buffer ignores close hooks */ }
        @Override public LoanedBuffer slice(long offset, long length) { return this; }
        @Override public LoanedBuffer view() { return this; }
        @Override public LoanedBuffer peek(long offset, long length) { return this; }
    }

    // =========================================================================
    // AbstractSecurityInterceptorTck — template method implementations
    // =========================================================================

    @Override
    protected SecurityInterceptor createInterceptor(SecurityProvider provider) {
        return new SecurityInterceptor(provider);
    }

    @Override
    protected SecurityProvider createSuccessProvider(PrincipalContext principal, StorageContext storage) {
        return new StubSuccessProvider(principal, storage);
    }

    @Override
    protected SecurityProvider createFailProvider() {
        return new StubFailProvider();
    }

    @Override
    protected LoanedBuffer createTokenBuffer() {
        return new NullLoanedBuffer();
    }

    @Override
    protected PrincipalContext createTestPrincipal() {
        return ImmutablePrincipal.ofTenant(UUID.randomUUID(), UUID.randomUUID(), Set.of("ROLE_USER"));
    }

    @Override
    protected boolean intercept(SecurityInterceptor interceptor, LoanedBuffer token, Runnable handler) {
        return interceptor.intercept(token, handler);
    }

    @Override
    protected boolean interceptPreAuthenticated(SecurityInterceptor interceptor,
                                                PrincipalContext principal,
                                                Runnable handler) {
        return interceptor.interceptPreAuthenticated(principal, handler);
    }

    @Override
    protected PrincipalContext createBridgeFailurePrincipal() {
        return new ThrowingTenantPrincipal();
    }
}
