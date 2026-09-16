/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.security.AbstractRoleMaskPopulationTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;

/**
 * Community binding for {@link AbstractRoleMaskPopulationTck}.
 *
 * <p>Wires a {@code SecurityInterceptor} over a stub success provider that
 * authenticates to the supplied principal, with the supplied registry, and runs
 * {@code intercept(...)} so the abstract's handler can observe the bound
 * {@code PRINCIPAL_CONTEXT} role mask.
 */
@DisplayName("Community: role-mask population TCK")
class CommunityRoleMaskPopulationTckTest extends AbstractRoleMaskPopulationTck {

    @Override
    protected boolean runIntercept(RoleRegistry registry,
                                   PrincipalContext principal,
                                   Runnable handler) {
        SecurityInterceptor interceptor =
                new SecurityInterceptor(new StubSuccessProvider(principal), registry);
        return interceptor.intercept(new NullLoanedBuffer(), handler);
    }

    private static final class StubSuccessProvider implements SecurityProvider {
        private final PrincipalContext principal;

        StubSuccessProvider(PrincipalContext principal) {
            this.principal = principal;
        }

        @Override public String providerId() { return "stub-mask"; }
        @Override public String providerName() { return "StubMask"; }
        @Override public AuthenticationResult authenticate(LoanedBuffer token) {
            return new AuthenticationResult(principal, ImmutableStorageContext.GLOBAL);
        }
        @Override public StorageContext systemStorageContext() { return ImmutableStorageContext.GLOBAL; }
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
}
