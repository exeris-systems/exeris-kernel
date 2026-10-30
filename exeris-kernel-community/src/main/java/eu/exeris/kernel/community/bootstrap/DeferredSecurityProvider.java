/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link SecurityProvider} that exists at {@code initialize()} and is built at {@code start()} — the
 * security counterpart of {@link DeferredHttpClientEngine}, for the same reason: the JWKS key set is
 * fetched through a client engine, which resolves {@code KernelProviders.MEMORY_ALLOCATOR} and, for an
 * {@code https} endpoint, a crypto provider at construction, and neither binding is visible while
 * {@link CommunitySecuritySubsystem} is still initialising.
 *
 * <p>Identity, priority and the system storage context are the ServiceLoader instance's, which carries
 * the same constants as the provider built at start. A token presented before {@link #start()} or after
 * {@link #close()} is denied ({@code EX-SEC-2002}), never admitted.
 */
// CloseResource: the template holds no resource, and the delegate is closed by close(), not by the
// methods that read it.
@SuppressWarnings("PMD.CloseResource")
final class DeferredSecurityProvider implements SecurityProvider {

    private static final String JWT_TYPE = "JWT";

    private final CommunitySecurityProvider template;
    private final Supplier<CommunitySecurityProvider> factory;
    // Read on every authenticate(): a closed provider's key cache still holds keys, so the flag, not the
    // delegate's own state, is what makes a token after close() a deny.
    private volatile boolean closed;

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile CommunitySecurityProvider delegate;

    /* default */ DeferredSecurityProvider(CommunitySecurityProvider template,
                                           Supplier<CommunitySecurityProvider> factory) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    /**
     * Builds the provider. A refusal — an {@code https} endpoint with no engine that verifies its
     * server, say — propagates and fails the boot.
     *
     * @throws IllegalStateException if this provider was already started or closed
     */
    /* default */ synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Security provider is closed");
        }
        if (delegate != null) {
            throw new IllegalStateException("Security provider is already started");
        }
        delegate = factory.get();
    }

    /** Releases the provider {@link #start()} built, if it built one. Idempotent. */
    /* default */ synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        CommunitySecurityProvider local = delegate;
        if (local != null) {
            local.close();
        }
    }

    @Override
    public String providerId() {
        return template.providerId();
    }

    @Override
    public String providerName() {
        return template.providerName();
    }

    @Override
    public int priority() {
        return template.priority();
    }

    /**
     * {@inheritDoc}
     *
     * @throws SecurityAuthenticationException ({@code EX-SEC-2002}) if the provider is not started,
     *         or for any reason the started provider denies the token
     */
    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        if (closed) {
            throw new SecurityAuthenticationException(JWT_TYPE, "provider-closed");
        }
        CommunitySecurityProvider local = delegate;
        if (local == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, "provider-not-started");
        }
        return local.authenticate(rawToken);
    }

    @Override
    public StorageContext systemStorageContext() {
        return template.systemStorageContext();
    }
}
