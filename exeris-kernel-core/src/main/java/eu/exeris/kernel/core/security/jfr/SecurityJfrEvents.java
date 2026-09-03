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

        @Label("Provider ID")
        @Description("Identifier of the SecurityProvider that performed authentication")
        public String providerId;

        @Label("Isolation Strategy")
        @Description("StorageContext isolation strategy (SHARED / SEPARATED_SCHEMA / DEDICATED)")
        public String isolationStrategy;

        @Label("Has Tenant")
        @Description("Whether the StorageContext carries a tenant isolation key")
        public boolean hasTenant;

        @Label("Duration (µs)")
        @Description("Wall-clock time for authenticate() in microseconds")
        public long durationMicros;
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

        @Label("Error Code")
        @Description("Canonical EX-SEC-* error code")
        public String errorCode;

        @Label("Drop Reason")
        @Description("One of SecurityDenialReason: NO_PROVIDER, TOKEN_MISSING, TOKEN_INVALID, "
                + "PROVIDER_ERROR, PRE_AUTH_BRIDGE_ERROR")
        public String dropReason;
    }

    /**
     * Emits a {@link SecurityContextMissingEvent} for a denial whose reason is enumerated.
     *
     * <p>The error code comes from the reason rather than from the caller, because the two had
     * already drifted apart in the javadoc that described them.
     *
     * @param reason why no security context could be established
     * @since 0.12.0
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

        @Label("Required Role")
        @Description("The role that the principal lacked")
        public String requiredRole;

        @Label("Principal ID Hash")
        @Description("Value-based hash of the principalId UUID — deterministic, safe for telemetry")
        public int principalIdHash;
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

        @Label("Isolation Strategy")
        @Description("The isolation strategy of the derived StorageContext")
        public String isolationStrategy;

        @Label("Has Tenant")
        @Description("Whether the derived context carries a tenant isolation key")
        public boolean hasTenant;
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

        @Label("Generated Class Found")
        @Description("Whether the generated RoleCheckRegistry class was resolved on the classpath")
        public boolean generatedClassFound;

        @Label("Method Count")
        @Description("Number of @RequiresRole-annotated entry points compiled into the registry")
        public int methodCount;
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
