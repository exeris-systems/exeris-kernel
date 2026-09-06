/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Container for the Citadel security path's {@code jdk.jfr.Event} subclasses and their emission
 * helpers.
 *
 * <p>{@link PrincipalBoundEvent} and {@link SecurityContextMissingEvent} fire from
 * {@link eu.exeris.kernel.core.security.SecurityInterceptor}, one or the other on every
 * intercepted request. {@link InsufficientPrivilegesEvent} fires from
 * {@link eu.exeris.kernel.core.security.CitadelGuard} on an RBAC rejection.
 * {@link StorageContextDerivedEvent} fires from
 * {@link eu.exeris.kernel.core.security.StorageContextBridge} on the pre-authenticated path.
 * {@link RoleRegistryLoadedEvent} fires once at bootstrap from
 * {@link eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader}. This class holds no state
 * of its own beyond grouping the events under one JFR category and is never instantiated.
 */
// UseExplicitTypes: 'var' is used only for JFR event locals; explicit types would
// duplicate the inner class name on the same line with zero type-safety gain.
@SuppressWarnings("PMD.UseExplicitTypes")
public final class SecurityJfrEvents {

    private SecurityJfrEvents() {}

    // =========================================================================
    // Event: PrincipalBound
    // =========================================================================

    /**
     * Emitted when the {@code SecurityInterceptor} successfully authenticates a request
     * and binds the {@link eu.exeris.kernel.spi.security.PrincipalContext} and
     * {@link eu.exeris.kernel.spi.security.StorageContext} into their
     * {@link java.lang.ScopedValue} slots.
     */
    @Name("eu.exeris.kernel.security.PrincipalBound")
    @Label("Principal Bound")
    @Category({"Exeris Kernel", "Security"})
    @Description("Emitted when PrincipalContext and StorageContext are bound for the current request scope")
    @StackTrace(false)
    public static final class PrincipalBoundEvent extends Event {

        /** {@code SecurityProvider.providerId()} of the provider that authenticated the request. */
        @Label("Provider ID")
        @Description("Identifier of the SecurityProvider that performed authentication")
        public String providerId;

        /**
         * Name of the bound {@code StorageContext}'s isolation strategy: {@code SHARED},
         * {@code SEPARATED_SCHEMA}, or {@code DEDICATED}.
         */
        @Label("Isolation Strategy")
        @Description("StorageContext isolation strategy (SHARED / SEPARATED_SCHEMA / DEDICATED)")
        public String isolationStrategy;

        /** {@code true} when the bound {@code StorageContext} carries a tenant isolation key. */
        @Label("Has Tenant")
        @Description("Whether the StorageContext carries a tenant isolation key")
        public boolean hasTenant;

        /**
         * Wall-clock time, in microseconds, from the start of the authentication attempt to this
         * event's commit. On the bearer-token path this spans {@code authenticate()} plus
         * role-mask enrichment; on the pre-authenticated path this is the
         * {@code StorageContextBridge.derive()} cost alone.
         */
        @Label("Duration (µs)")
        @Description("Wall-clock time for authenticate() in microseconds")
        public long durationMicros;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public PrincipalBoundEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    /**
     * Emits a {@link PrincipalBoundEvent} using a zero-overhead guard pattern.
     *
     * @param providerId        the SecurityProvider that authenticated the token
     * @param isolationStrategy string representation of the isolation strategy
     * @param hasTenant         whether the storage context carries a tenant key
     * @param startNanos        {@code System.nanoTime()} at the start of authentication
     */
    public static void emitPrincipalBound(String providerId,
                                          String isolationStrategy,
                                          boolean hasTenant,
                                          long startNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new PrincipalBoundEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerId = providerId;
        event.isolationStrategy = isolationStrategy;
        event.hasTenant = hasTenant;
        event.durationMicros = (System.nanoTime() - startNanos) / 1_000L;
        event.commit();
    }

    // =========================================================================
    // Event: SecurityContextMissing
    // =========================================================================

    /**
     * Emitted when a request is dropped because no security context can be established.
     * Carries a canonical {@code EX-SEC-*} error code for the fail-closed gate activation.
     */
    @Name("eu.exeris.kernel.security.SecurityContextMissing")
    @Label("Security Context Missing")
    @Category({"Exeris Kernel", "Security"})
    @Description("Emitted when a request is dropped at the L1 boundary — no security context could be established")
    @StackTrace(false)
    public static final class SecurityContextMissingEvent extends Event {

        /** The denial's {@link SecurityDenialReason#errorCode()}: {@code EX-SEC-2001} or {@code EX-SEC-2002}. */
        @Label("Error Code")
        @Description("Canonical EX-SEC-* error code")
        public String errorCode;

        /** {@link SecurityDenialReason} constant name identifying which check produced the denial. */
        @Label("Drop Reason")
        @Description("One of SecurityDenialReason: NO_PROVIDER, TOKEN_MISSING, TOKEN_INVALID, "
                + "PROVIDER_ERROR, PRE_AUTH_BRIDGE_ERROR")
        public String dropReason;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public SecurityContextMissingEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    /**
     * Emits a {@link SecurityContextMissingEvent} for a denial whose reason is enumerated.
     *
     * <p>The error code comes from the reason rather than from the caller, because the two had
     * already drifted apart in the javadoc that described them.
     *
     * @param reason why no security context could be established
     * @since 0.12
     */
    public static void emitContextMissing(SecurityDenialReason reason) {
        emitContextMissing(reason.errorCode(), reason.name());
    }

    /**
     * Emits a {@link SecurityContextMissingEvent}.
     *
     * @param errorCode  canonical EX-SEC-* code
     * @param dropReason short, safe-for-telemetry reason code
     */
    private static void emitContextMissing(String errorCode, String dropReason) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new SecurityContextMissingEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.errorCode = errorCode;
        event.dropReason = dropReason;
        event.commit();
    }

    // =========================================================================
    // Event: InsufficientPrivileges (EX-SEC-2003)
    // =========================================================================

    /**
     * Emitted when a principal lacks the required role for an operation.
     * Maps to error code {@code EX-SEC-2003} — RBAC gate enforcement by
     * the Citadel Guard.
     */
    @Name("eu.exeris.kernel.security.InsufficientPrivileges")
    @Label("Insufficient Privileges")
    @Category({"Exeris Kernel", "Security"})
    @Description("Emitted when a principal is rejected due to missing required role (RBAC gate)")
    @StackTrace(false)
    public static final class InsufficientPrivilegesEvent extends Event {

        /** Name of the role required for the operation that the principal did not hold. */
        @Label("Required Role")
        @Description("The role that the principal lacked")
        public String requiredRole;

        /** Deterministic hash of the principal's UUID; identifies the principal without recording it. */
        @Label("Principal ID Hash")
        @Description("Value-based hash of the principalId UUID — deterministic, safe for telemetry")
        public int principalIdHash;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public InsufficientPrivilegesEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    /**
     * Emits an {@link InsufficientPrivilegesEvent}.
     *
     * @param requiredRole    role that was required
     * @param principalIdHash value-based hash of the principalId UUID (not the raw UUID)
     */
    public static void emitInsufficientPrivileges(String requiredRole, int principalIdHash) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new InsufficientPrivilegesEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.requiredRole = requiredRole;
        event.principalIdHash = principalIdHash;
        event.commit();
    }

    // =========================================================================
    // Event: StorageContextDerived
    // =========================================================================

    /**
     * Emitted by the StorageContextBridge when a {@link eu.exeris.kernel.spi.security.StorageContext}
     * is derived from an active {@link eu.exeris.kernel.spi.security.PrincipalContext}.
     */
    @Name("eu.exeris.kernel.security.StorageContextDerived")
    @Label("Storage Context Derived")
    @Category({"Exeris Kernel", "Security"})
    @Description("Emitted when StorageContextBridge derives a StorageContext from the active PrincipalContext")
    @StackTrace(false)
    public static final class StorageContextDerivedEvent extends Event {

        /**
         * Name of the derived {@code StorageContext}'s isolation strategy: {@code SHARED},
         * {@code SEPARATED_SCHEMA}, or {@code DEDICATED}.
         */
        @Label("Isolation Strategy")
        @Description("The isolation strategy of the derived StorageContext")
        public String isolationStrategy;

        /** {@code true} when the derived {@code StorageContext} carries a tenant isolation key. */
        @Label("Has Tenant")
        @Description("Whether the derived context carries a tenant isolation key")
        public boolean hasTenant;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public StorageContextDerivedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    /**
     * Emits a {@link StorageContextDerivedEvent}.
     *
     * @param isolationStrategy string representation of the isolation strategy
     * @param hasTenant         whether the derived context carries a tenant key
     */
    public static void emitStorageContextDerived(String isolationStrategy, boolean hasTenant) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new StorageContextDerivedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.isolationStrategy = isolationStrategy;
        event.hasTenant = hasTenant;
        event.commit();
    }

    // =========================================================================
    // Event: RoleRegistryLoaded (bootstrap, one-shot)
    // =========================================================================

    /**
     * Emitted once at bootstrap when the generated {@code RoleCheckRegistry} is
     * resolved (or found absent). Lets operators distinguish "no
     * {@code @RequiresRole} compiled" ({@code found == false}) from "registry
     * loaded with N methods" ({@code found == true}, {@code methodCount == N}),
     * and from a load failure (also {@code found == false}). Carries no secrets.
     */
    @Name("eu.exeris.kernel.security.RoleRegistryLoaded")
    @Label("Role Registry Loaded")
    @Category({"Exeris Kernel", "Security"})
    @Description("Emitted once at bootstrap when the generated @RequiresRole RoleCheckRegistry is resolved")
    @StackTrace(false)
    public static final class RoleRegistryLoadedEvent extends Event {

        /** {@code true} when the generated {@code RoleCheckRegistry} class was resolved on the classpath. */
        @Label("Generated Class Found")
        @Description("Whether the generated RoleCheckRegistry class was resolved on the classpath")
        public boolean generatedClassFound;

        /**
         * Number of {@code @RequiresRole}-annotated entry points compiled into the registry;
         * {@code 0} when {@link #generatedClassFound} is {@code false}.
         */
        @Label("Method Count")
        @Description("Number of @RequiresRole-annotated entry points compiled into the registry")
        public int methodCount;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public RoleRegistryLoadedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    /**
     * Emits a {@link RoleRegistryLoadedEvent}. Single-phase commit — bootstrap
     * runs on a platform thread, but we still avoid begin()/work/commit()
     * straddle per the VT-JFR lore.
     *
     * @param generatedClassFound whether the generated class was resolved
     * @param methodCount         number of annotated entry points ({@code 0} when absent)
     */
    public static void emitRoleRegistryLoaded(boolean generatedClassFound, int methodCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new RoleRegistryLoadedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.generatedClassFound = generatedClassFound;
        event.methodCount = methodCount;
        event.commit();
    }
}
