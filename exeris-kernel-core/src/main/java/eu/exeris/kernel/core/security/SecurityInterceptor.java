/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.core.security.jfr.SecurityDenialReason;
import eu.exeris.kernel.core.security.jfr.SecurityJfrEvents;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
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
 *   SecurityContextMissing  ← gate activation; the reason is a SecurityDenialReason and
 *                             carries its own EX-SEC code (2001 = nothing to validate,
 *                             2002 = validation attempted and failed)
 * </pre>
 *
 * <h2>The Wall</h2>
 * <p>Imports only {@code exeris-kernel-spi}. No JWT, no BouncyCastle, no Spring Security.
 *
 * @since 0.5
 * @see SecurityProvider
 * @see StorageContextBridge
 * @see CitadelGuard
 * @see KernelProviders#PRINCIPAL_CONTEXT
 * @see KernelProviders#STORAGE_CONTEXT
 */
@SuppressWarnings({"java:S6548", "java:S6206"})
public final class SecurityInterceptor {

    /** Sentinel mask meaning "no registry-resolved roles" — the fail-closed default. */
    private static final long EMPTY_MASK = 0L;

    private final SecurityProvider provider;
    private final RoleRegistry roleRegistry;

    /**
     * Constructs a {@code SecurityInterceptor} backed by the given provider and
     * the fail-closed {@linkplain GeneratedRoleRegistryLoader#empty() empty}
     * role registry.
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
        this(provider, GeneratedRoleRegistryLoader.empty());
    }

    /**
     * Constructs a {@code SecurityInterceptor} backed by the given provider and
     * a build-time {@link RoleRegistry} (ADR-014 §5).
     *
     * <p>When {@code roleRegistry.methodCount() > 0} the interceptor resolves
     * the authenticated principal's role names against the registry and binds a
     * {@link MaskedPrincipal} carrying the precomputed {@code roleMask()} into
     * the request scope, so downstream {@code @RequiresRole} checks resolve to a
     * primitive {@code AND}/{@code EQ}. When the registry is empty (no
     * {@code @RequiresRole} compiled anywhere) the original principal is bound
     * unchanged — no allocation, mask stays {@code 0L}, fail-closed.
     *
     * @param provider     the security provider; must not be {@code null}
     * @param roleRegistry the build-time role registry; must not be {@code null}
     *                     (pass {@link GeneratedRoleRegistryLoader#empty()} when none)
     */
    public SecurityInterceptor(SecurityProvider provider, RoleRegistry roleRegistry) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.roleRegistry = Objects.requireNonNull(roleRegistry, "roleRegistry must not be null");
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
            SecurityJfrEvents.emitContextMissing(SecurityDenialReason.TOKEN_INVALID);
            return false;
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — any provider failure = no context, Fail-Closed
            SecurityJfrEvents.emitContextMissing(SecurityDenialReason.PROVIDER_ERROR);
            return false;
        } catch (Error ex) { //NOPMD AvoidCatchingGenericException — JFR must fire before JVM-fatal errors propagate
            SecurityJfrEvents.emitContextMissing(SecurityDenialReason.PROVIDER_ERROR);
            throw ex;
        }

        PrincipalContext principal = enrichWithRoleMask(result.principal());
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
     * Resolves the principal's role names against the registry into a precomputed
     * {@code roleMask} and wraps the principal in a {@link MaskedPrincipal}.
     *
     * <p>When the registry carries no {@code @RequiresRole} entry points
     * ({@code methodCount() == 0}) the original principal is returned unchanged —
     * no allocation, mask stays {@code 0L}. A principal that already exposes a
     * non-zero {@code roleMask()} (e.g. a pre-masked system principal) is left
     * untouched so it is never double-wrapped or downgraded.
     *
     * @param principal the authenticated principal
     * @return the principal to bind into the request scope
     */
    private PrincipalContext enrichWithRoleMask(PrincipalContext principal) {
        if (roleRegistry.methodCount() == 0 || principal.roleMask() != EMPTY_MASK) {
            return principal;
        }
        long mask = EMPTY_MASK;
        for (String role : principal.roles()) {
            mask |= roleRegistry.roleNameToMask(role);
        }
        if (mask == EMPTY_MASK) {
            return principal;
        }
        return new MaskedPrincipal(principal, mask);
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
     * Binds a pre-authenticated identity into the current scope and runs
     * {@code requestHandler} within that scope.
     *
     * <h2>Citadel Contract — Isolation Invariant</h2>
     * <p>The caller supplies a pre-authenticated {@link PrincipalContext} (e.g., from a
     * trusted internal gateway, an mTLS service-mesh identity, or a resumed HTTP/2 session
     * with pre-validated credentials). The caller MUST NOT and CANNOT supply a
     * {@link eu.exeris.kernel.spi.security.StorageContext} —
     * {@link StorageContextBridge#derive(PrincipalContext)} is called internally.
     * This is an unconditional invariant: the isolation strategy is always kernel-derived,
     * never caller-controlled.
     *
     * <h2>Fail-Closed Bridge</h2>
     * <p>If {@link StorageContextBridge#derive(PrincipalContext)} throws for any reason,
     * the request is dropped immediately — the handler is <b>never invoked</b>. A JFR
     * {@code SecurityContextMissing} event is emitted ({@code EX-SEC-2002},
     * {@code "PRE_AUTH_BRIDGE_ERROR"}) and {@code false} is returned to the caller.
     * {@link Error} subclasses are caught solely to emit the JFR event before being
     * <b>re-thrown unconditionally</b>.
     *
     * <h2>JFR Waterfall</h2>
     * <pre>
     *   StorageContextDerived  ← emitted by StorageContextBridge.derive() on success
     *   PrincipalBound         ← emitted after successful derivation (durationMicros = bridge cost)
     *   SecurityContextMissing ← emitted on bridge failure (EX-SEC-2002 / PRE_AUTH_BRIDGE_ERROR)
     * </pre>
     *
     * <h2>ScopedValue Binding</h2>
     * <p>Both {@link eu.exeris.kernel.spi.context.KernelProviders#PRINCIPAL_CONTEXT} and
     * {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT} are bound
     * atomically in a single {@link ScopedValue.Carrier}. Virtual threads forked within
     * {@code requestHandler} on the request thread observe both slots; a scope that forks must carry
     * them explicitly (ADR-066).
     *
     * @param principal       pre-authenticated identity; must not be {@code null}
     * @param requestHandler  logic to execute within the authenticated scope; must not be {@code null}
     * @return {@code true} if the handler was invoked (bridge succeeded);
     *         {@code false} if the request was dropped at the Citadel gate
     * @throws NullPointerException if {@code principal} or {@code requestHandler} is {@code null}
     */
    @SuppressWarnings("java:S1181")
    public boolean interceptPreAuthenticated(PrincipalContext principal, Runnable requestHandler) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requestHandler, "requestHandler must not be null");

        long startNanos = System.nanoTime();

        StorageContext storage;
        try {
            storage = StorageContextBridge.derive(principal);
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — any bridge failure = fail-closed
            SecurityJfrEvents.emitContextMissing(SecurityDenialReason.PRE_AUTH_BRIDGE_ERROR);
            return false;
        } catch (Error ex) { //NOPMD AvoidCatchingGenericException — emit JFR then re-throw
            SecurityJfrEvents.emitContextMissing(SecurityDenialReason.PRE_AUTH_BRIDGE_ERROR);
            throw ex;
        }

        SecurityJfrEvents.emitPrincipalBound(
                provider.providerId(),
                storage.strategy().name(),
                storage.isolationKey().isPresent(),
                startNanos
        );

        ScopedValue
                .where(KernelProviders.PRINCIPAL_CONTEXT, enrichWithRoleMask(principal))
                .where(KernelProviders.STORAGE_CONTEXT, storage)
                .run(requestHandler);

        return true;
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

