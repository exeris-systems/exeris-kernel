/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.core.security.jfr.SecurityJfrEvents;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Objects;

/**
 * Core: L1 Citadel — Scoped Identity Propagation Orchestrator.
 *
 * <h2>Mission</h2>
 * <p>{@code SecurityInterceptor} is the <em>only</em> place in the kernel where
 * a raw transport token is authenticated and identity is bound to the execution scope.
 * It acts as the impenetrable boundary between the public transport edge and
 * the protected kernel internals.
 *
 * <h2>Fail-Closed Gate</h2>
 * <p>If the {@link SecurityProvider} is not bound (no provider on classpath or
 * not bootstrapped), or if authentication fails for any reason, the request is
 * <b>dropped immediately</b>. No fallback, no anonymous access. A JFR
 * {@code SecurityContextMissing} event is emitted for every drop so that
 * operators can observe the gate activation without log verbosity.
 *
 * <h2>ScopedValue Binding Protocol</h2>
 * <p>On successful authentication, both contexts are bound atomically into
 * a single {@link ScopedValue.Carrier}:
 * <pre>{@code
 * ScopedValue
 *     .where(KernelProviders.PRINCIPAL_CONTEXT, result.principal())
 *     .where(KernelProviders.STORAGE_CONTEXT,   result.storage())
 *     .run(requestHandler);
 * }</pre>
 * <p>Every virtual thread spawned within {@code requestHandler} inherits both
 * slots automatically — no constructor injection, no {@code ThreadLocal} (banned).
 *
 * <h2>Isolation Bridge</h2>
 * <p>The {@link StorageContext} bound here is produced either:
 * <ul>
 *   <li>Directly by the {@link SecurityProvider#authenticate(LoanedBuffer)} call
 *       (Enterprise path — authentication result already carries the correct
 *       strategy).</li>
 *   <li>Via {@link StorageContextBridge#derive(PrincipalContext)} when the provider
 *       returns a minimal result and bridge derivation is configured.</li>
 * </ul>
 * <p>The Persistence subsystem reads {@link KernelProviders#STORAGE_CONTEXT} and
 * has zero knowledge of this Security class.
 *
 * <h2>JFR-First Waterfall</h2>
 * <pre>
 *   PrincipalBound          ← successful bind (durationMicros, strategy, hasTenant)
 *   SecurityContextMissing  ← gate activation (EX-SEC-2001 / NO_PROVIDER / TOKEN_INVALID)
 * </pre>
 *
 * <h2>The Wall</h2>
 * <p>Imports only {@code exeris-kernel-spi}. No JWT, no BouncyCastle, no Spring Security.
 *
 * @since 0.5.0
 * @see SecurityProvider
 * @see StorageContextBridge
 * @see CitadelGuard
 * @see KernelProviders#PRINCIPAL_CONTEXT
 * @see KernelProviders#STORAGE_CONTEXT
 */
@SuppressWarnings({"java:S6548", "java:S6206"})
public final class SecurityInterceptor {

    private final SecurityProvider provider;

    /**
     * Constructs a {@code SecurityInterceptor} backed by the given provider.
     *
     * <p>Typically called once during bootstrap, after
     * {@link java.util.ServiceLoader} selects the highest-priority
     * {@link SecurityProvider}. The constructed interceptor is then shared
     * across all virtual threads — it must be thread-safe, and so must the
     * supplied provider.
     *
     * @param provider the security provider to use for token authentication;
     *                 must not be {@code null}
     */
    public SecurityInterceptor(SecurityProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    /**
     * Authenticates the raw token, binds identity into the current scope, and
     * runs {@code requestHandler} within that scope.
     *
     * <h2>Fail-Closed Gate</h2>
     * <p>If authentication fails (expired token, malformed token, revoked credentials),
     * the handler is <b>never invoked</b>. A JFR {@code SecurityContextMissing} event
     * is emitted and {@code false} is returned to the caller so it can send the
     * appropriate protocol-level rejection (e.g., HTTP 401, QUIC stream reset).
     *
     * <p>{@link Error} subclasses (e.g., {@link OutOfMemoryError},
     * {@link StackOverflowError}) are caught solely to emit the JFR event before
     * being <b>re-thrown unconditionally</b> — {@code Error} is never recoverable.
     *
     * <h2>ScopedValue Binding</h2>
     * <p>Both {@link KernelProviders#PRINCIPAL_CONTEXT} and
     * {@link KernelProviders#STORAGE_CONTEXT} are bound in a single atomic
     * {@link ScopedValue.Carrier} — they are always consistent within the scope.
     *
     * @param rawToken      token buffer delivered by the transport layer;
     *                      ownership remains with the caller
     * @param requestHandler logic to execute within the authenticated scope
     * @return {@code true} if the handler was invoked (authentication succeeded);
     *         {@code false} if the request was dropped at the gate
     */
    @SuppressWarnings({"java:S1181", "java:S6548"})
    public boolean intercept(LoanedBuffer rawToken, Runnable requestHandler) {
        long startNanos = System.nanoTime();

        AuthenticationResult result;
        try {
            result = provider.authenticate(rawToken);
        } catch (SecurityAuthenticationException _) {
            SecurityJfrEvents.emitContextMissing(KernelErrorCodes.EX_SEC_2002, "TOKEN_INVALID");
            return false;
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — any provider failure = no context, Fail-Closed
            SecurityJfrEvents.emitContextMissing(KernelErrorCodes.EX_SEC_2002, "PROVIDER_ERROR");
            return false;
        } catch (Error ex) { //NOPMD AvoidCatchingGenericException — JFR must fire before JVM-fatal errors propagate
            SecurityJfrEvents.emitContextMissing(KernelErrorCodes.EX_SEC_2002, "PROVIDER_ERROR");
            throw ex;
        }

        PrincipalContext principal = result.principal();
        StorageContext   storage   = result.storage();

        SecurityJfrEvents.emitPrincipalBound(
                provider.providerId(),
                storage.strategy().name(),
                storage.isolationKey().isPresent(),
                startNanos
        );

        ScopedValue
                .where(KernelProviders.PRINCIPAL_CONTEXT, principal)
                .where(KernelProviders.STORAGE_CONTEXT, storage)
                .run(requestHandler);

        return true;
    }

    /**
     * Binds a system-level identity scope and runs {@code task} within it.
     *
     * <p>Used for internal kernel operations that run outside of a user request
     * (bootstrap tasks, outbox polling, migrations). Binds:
     * <ul>
     *   <li>{@link KernelProviders#PRINCIPAL_CONTEXT} — the supplied system principal</li>
     *   <li>{@link KernelProviders#STORAGE_CONTEXT}   — the provider's
     *       {@link SecurityProvider#systemStorageContext()}</li>
     * </ul>
     *
     * <p>This method always invokes {@code task} — it does not perform token validation
     * and cannot fail with a gate drop. Use only for trusted internal operations.
     *
     * @param systemPrincipal the principal representing the kernel/system actor
     * @param task            the task to run within the system scope
     */
    public void runAsSystem(PrincipalContext systemPrincipal, Runnable task) {
        Objects.requireNonNull(systemPrincipal, "systemPrincipal must not be null");
        Objects.requireNonNull(task, "task must not be null");

        StorageContext systemStorage = provider.systemStorageContext();

        ScopedValue
                .where(KernelProviders.PRINCIPAL_CONTEXT, systemPrincipal)
                .where(KernelProviders.STORAGE_CONTEXT, systemStorage)
                .run(task);
    }

    /**
     * Returns the {@link SecurityProvider} backing this interceptor.
     *
     * <p>Useful for bootstrap introspection (e.g., checking
     * {@link SecurityProvider#providerId()} in JFR bootstrap events).
     *
     * @return the configured security provider
     */
    public SecurityProvider provider() {
        return provider;
    }
}

