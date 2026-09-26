/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security.tck;

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

@DisplayName("Core: SecurityInterceptor TCK")
class CoreSecurityInterceptorTckTest extends AbstractSecurityInterceptorTck<SecurityInterceptor> {

    @Override
    protected SecurityInterceptor createInterceptor(SecurityProvider provider) {
        return new SecurityInterceptor(provider);
    }

    @Override
    protected SecurityProvider createSuccessProvider(PrincipalContext principal, StorageContext storage) {
        return new SecurityProvider() {
            @Override
            public String providerId() {
                return "core-security-tck-success";
            }

            @Override
            public String providerName() {
                return "CoreSecurityTckSuccessProvider";
            }

            @Override
            public AuthenticationResult authenticate(LoanedBuffer rawToken) {
                return new AuthenticationResult(principal, storage);
            }

            @Override
            public StorageContext systemStorageContext() {
                return ImmutableStorageContext.GLOBAL;
            }
        };
    }

    @Override
    protected SecurityProvider createFailProvider() {
        return new SecurityProvider() {
            @Override
            public String providerId() {
                return "core-security-tck-fail";
            }

            @Override
            public String providerName() {
                return "CoreSecurityTckFailProvider";
            }

            @Override
            public AuthenticationResult authenticate(LoanedBuffer rawToken) {
                throw new SecurityAuthenticationException("TCK", "forced-failure");
            }

            @Override
            public StorageContext systemStorageContext() {
                return ImmutableStorageContext.GLOBAL;
            }
        };
    }

    @Override
    protected LoanedBuffer createTokenBuffer() {
        return new LoanedBuffer() {
            @Override
            public MemorySegment segment() {
                return MemorySegment.NULL;
            }

            @Override
            public long size() {
                return 0L;
            }

            @Override
            public long capacity() {
                return 0L;
            }

            @Override
            public LoanedBuffer slice(long offset, long length) {
                return this;
            }

            @Override
            public LoanedBuffer view() {
                return this;
            }

            @Override
            public LoanedBuffer peek(long offset, long length) {
                return this;
            }

            @Override
            public void retain() {
                // no-op in test stub
            }

            @Override
            public void close() {
                // no-op in test stub
            }

            @Override
            public int refCount() {
                return 1;
            }

            @Override
            public void setSize(long newSize) {
                // no-op in test stub
            }

            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public void addCloseAction(Runnable action) {
                // no-op in test stub
            }
        };
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
        return new PrincipalContext() {
            @Override public UUID principalId() { return UUID.randomUUID(); }
            @Override public Optional<UUID> tenantId() { throw new RuntimeException("bridge-test"); }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> scopes() { return Set.of(); }
        };
    }
}