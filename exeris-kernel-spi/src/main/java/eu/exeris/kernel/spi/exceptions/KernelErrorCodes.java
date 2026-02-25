/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

/**
 * Canonical registry of all {@code EX-[DOMAIN]-[ID]} error codes used across the Exeris Kernel.
 *
 * <h2>Design</h2>
 * <p>Codes are exposed as {@code public static final String} constants so that:
 * <ol>
 *   <li>The JIT can inline them as compile-time constants (no field lookup).</li>
 *   <li>Log-scrapers and the Black-Box binary serializer can pattern-match against a
 *       fixed, well-known string pool.</li>
 *   <li>There is a single source of truth – no duplicated string literals in subclasses.</li>
 * </ol>
 *
 * <h2>The Wall</h2>
 * <p>This class is part of {@code exeris-kernel-spi} and is therefore <em>blind</em> to any
 * implementation detail (io_uring, JDBC, Netty). It contains only structured identifiers.
 *
 * <h2>Format</h2>
 * <pre>
 * EX – mandatory Exeris prefix
 * [DOMAIN] – 3-letter domain tag
 * [ID]     – 4-digit monotonic identifier within the domain
 * </pre>
 *
 * @since 0.5.0
 */
@SuppressWarnings("unused") // Codes are API contracts — referenced by future exception subclasses and external scrapers
public final class KernelErrorCodes {

    // -----------------------------------------------------------------------
    // EX-MEM – Memory / Off-Heap subsystem
    // -----------------------------------------------------------------------

    /**
     * Off-heap allocator exhausted: the requested byte count exceeds the
     * remaining capacity of the {@code MemoryAllocator} tier.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} requestedBytes</li>
     *   <li>index 1 – {@code long} availableBytes</li>
     * </ul>
     */
    public static final String EX_MEM_1001 = "EX-MEM-1001";

    /**
     * Off-heap arena leak detected: a {@code MemorySegment} was not returned
     * to its parent arena before the arena's lifecycle ended.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} segmentAddress</li>
     *   <li>index 1 – {@code long} segmentByteSize</li>
     * </ul>
     */
    public static final String EX_MEM_1002 = "EX-MEM-1002";

    /**
     * Allocation hint conflict: two subsystems requested incompatible
     * {@code AllocationHint} tiers for the same buffer slot.
     */
    public static final String EX_MEM_1003 = "EX-MEM-1003";

    // -----------------------------------------------------------------------
    // EX-BOOT – Bootstrap / Subsystem lifecycle
    // -----------------------------------------------------------------------

    /**
     * Subsystem initialization or startup failure.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <p>This code is shared across multiple bootstrap pathways and therefore
     * does not expose a stable {@code rawArgs} layout for Black-Box decoding.
     * Callers may attach implementation-specific {@code rawArgs}, but
     * Black-Box consumers must treat them as opaque and must not rely on a
     * particular arity or field order.</p>
     */
    public static final String EX_BOOT_0002 = "EX-BOOT-0002";

    /**
     * Kernel bootstrap sequence aborted: a mandatory subsystem did not
     * complete initialization within the deadline.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} subsystemName</li>
     *   <li>index 1 – {@code long} deadlineMs</li>
     * </ul>
     */
    public static final String EX_BOOT_0003 = "EX-BOOT-0003";

    /**
     * Memory provider bootstrap failure: the {@code MemoryProvider} could not
     * initialise its off-heap tier (e.g., insufficient system memory, mmap permission
     * denied, or missing native library).
     *
     * <p>This code is intentionally separate from {@link #EX_BOOT_0002} to guarantee
     * a stable, single rawArgs schema per error code – a hard requirement of the
     * binary Black-Box telemetry contract.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisEnterprise/GlobalArbiter"})</li>
     *   <li>index 1 – {@code long}   requestedBytes ({@code -1} if unknown)</li>
     * </ul>
     */
    public static final String EX_BOOT_0004 = "EX-BOOT-0004";

    // -----------------------------------------------------------------------
    // EX-BOOT – Telemetry bootstrap
    // -----------------------------------------------------------------------

    /**
     * Telemetry provider failed to initialise one or more sinks.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason</li>
     * </ul>
     */
    public static final String EX_BOOT_3001 = "EX-BOOT-3001";

    // -----------------------------------------------------------------------
    // EX-NET – Transport / Network layer
    // -----------------------------------------------------------------------

    /**
     * TLS/Crypto operation failure (handshake, wrap, unwrap, or shutdown).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    nativeErrorCode (provider-specific error code; -1 if N/A)</li>
     *   <li>index 1 – {@code String} detail</li>
     * </ul>
     */
    public static final String EX_NET_2001 = "EX-NET-2001";

    /**
     * Crypto provider bootstrap failure: the {@code KernelCryptoProvider} could not
     * initialise its engine (e.g., missing native cryptographic library, invalid certificate,
     * or insufficient off-heap budget).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (which provider failed to initialise)</li>
     *   <li>index 1 – {@code String} reason       (failure cause — static constant, never formatted)</li>
     * </ul>
     */
    public static final String EX_NET_2002 = "EX-NET-2002";

    /**
     * Transport-level protocol handshake or bind failure.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code int}    port (or -1 if unknown)</li>
     * </ul>
     */
    public static final String EX_NET_4001 = "EX-NET-4001";

    /**
     * Transport send failure: a frame or datagram could not be delivered.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   bytesSent</li>
     * </ul>
     */
    public static final String EX_NET_4002 = "EX-NET-4002";

    /**
     * Transport receive timeout: no bytes arrived within the deadline.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   timeoutMs</li>
     * </ul>
     */
    public static final String EX_NET_4003 = "EX-NET-4003";

    // -----------------------------------------------------------------------
    // EX-PERS – Persistence subsystem
    // -----------------------------------------------------------------------

    /**
     * Persistence provider bootstrap failure: the {@code PersistenceProvider} could not
     * initialise its engine (e.g., connection refused, authentication failed, or
     * missing native driver library).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisEnterprise/PgNative"})</li>
     *   <li>index 1 – {@code String} connectionUrl</li>
     * </ul>
     */
    public static final String EX_PERS_5001 = "EX-PERS-5001";

    /**
     * Persistence connection acquisition failure: pool exhausted or timeout.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code long}   timeoutMs</li>
     *   <li>index 2 – {@code int}    activeConnections</li>
     * </ul>
     */
    public static final String EX_PERS_5002 = "EX-PERS-5002";

    /**
     * Persistence query execution failure: protocol error, SQL error, or I/O.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} sqlState (PostgreSQL SQLSTATE code)</li>
     *   <li>index 1 – {@code String} detail</li>
     * </ul>
     */
    public static final String EX_PERS_5003 = "EX-PERS-5003";

    /**
     * Persistence authentication failure: SCRAM/MD5/cleartext auth rejected.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} authMechanism</li>
     *   <li>index 1 – {@code String} serverMessage</li>
     * </ul>
     */
    public static final String EX_PERS_5004 = "EX-PERS-5004";

    /**
     * Persistence transport failure: socket I/O error during read/write.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   fileDescriptor</li>
     *   <li>index 2 – {@code int}    errno</li>
     * </ul>
     */
    public static final String EX_PERS_5005 = "EX-PERS-5005";

    /**
     * Persistence interceptor initialization error: a {@code ConnectionInterceptor}
     * failed to prepare the connection for the given isolation context
     * (e.g., RLS {@code SET LOCAL} rejected, schema switch refused).
     *
     * <p>The engine discards the connection on this error — it MUST NOT be
     * returned to the pool.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} interceptorClass (simple class name of the failing interceptor)</li>
     *   <li>index 1 – {@code String} isolationKey (value from {@code StorageContext.isolationKey()},
     *                                or {@code "[none]"} for system-scope)</li>
     * </ul>
     */
    public static final String EX_PERS_5006 = "EX-PERS-5006";

    // -----------------------------------------------------------------------
    // EX-SEC – Security / Principal context
    // -----------------------------------------------------------------------

    /**
     * PrincipalContext missing from the current {@code ScopedValue} slot.
     */
    public static final String EX_SEC_2001 = "EX-SEC-2001";

    /**
     * Token validation failed (expired, malformed, or revoked).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} tokenType (e.g. "JWT", "OPAQUE")</li>
     *   <li>index 1 – {@code String} failureReason (e.g. "expired", "malformed", "revoked")</li>
     * </ul>
     */
    public static final String EX_SEC_2002 = "EX-SEC-2002";

    /**
     * Insufficient privileges (RBAC): principal lacks required role(s).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} requiredRole</li>
     * </ul>
     */
    public static final String EX_SEC_2003 = "EX-SEC-2003";

    // -----------------------------------------------------------------------
    // EX-RUN – Runtime / Scheduler
    // -----------------------------------------------------------------------

    /**
     * Virtual Thread pinned the carrier: a blocking call inside a virtual thread
     * has prevented the carrier from being reused.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long}   blockTimeMs</li>
     *   <li>index 1 – {@code String} carrierThreadName</li>
     * </ul>
     */
    public static final String EX_RUN_3002 = "EX-RUN-3002";

    // -----------------------------------------------------------------------
    // Constructor – utility class, no instantiation
    // -----------------------------------------------------------------------

    private KernelErrorCodes() {
        // Utility class – no instances
    }
}
