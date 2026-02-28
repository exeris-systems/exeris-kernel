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
     * Circular dependency detected in the subsystem graph: Kahn's topological sort
     * found that the declared {@code dependsOn()} relationships form a cycle, making
     * a valid boot order impossible.
     *
     * <p>This is an <em>unrecoverable architectural defect</em>. The kernel halts
     * immediately; no degraded mode or partial boot is attempted.
     * Thrown by {@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException}.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String[]} cycleMembers (ordered subsystem names forming the cycle)</li>
     * </ul>
     */
    public static final String EX_BOOT_0001 = "EX-BOOT-0001";

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

    /**
     * Transport engine bootstrap failure: the {@code TransportProvider} could not
     * create a {@code TransportEngine} (e.g., missing native library, socket allocation
     * failure, insufficient off-heap budget for slab pools).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName (which transport failed)</li>
     *   <li>index 1 – {@code String} reason        (static failure description)</li>
     * </ul>
     */
    public static final String EX_NET_4004 = "EX-NET-4004";

    /**
     * Transport engine start failure: the engine was created but could not be started
     * (e.g., port already in use, carrier loop thread creation failed, io_uring ring
     * setup returned an error).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName (which engine failed to start)</li>
     *   <li>index 1 – {@code int}    port          (port that could not be bound; -1 if N/A)</li>
     * </ul>
     */
    public static final String EX_NET_4005 = "EX-NET-4005";

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
     *   <li>index 1 – {@code String} sanitizedConnectionUrl — userinfo ({@code user:password@})
     *       stripped before capture to prevent credential leakage into telemetry dumps</li>
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

    /**
     * No {@link eu.exeris.kernel.spi.persistence.PersistenceProvider} found on the classpath.
     *
     * <p>Kernel start is aborted. Add {@code exeris-kernel-community} or
     * {@code exeris-kernel-enterprise} to the runtime dependencies.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} message (human-readable diagnostic)</li>
     * </ul>
     */
    public static final String EX_PERS_5007 = "EX-PERS-5007";

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

    /**
     * StorageContext missing from the current {@code ScopedValue} slot.
     */
    public static final String EX_SEC_2004 = "EX-SEC-2004";

    // -----------------------------------------------------------------------
    // EX-GRPH – Graph subsystem (L2 Data Synthesis)
    // -----------------------------------------------------------------------

    /**
     * Graph engine bootstrap failure: the {@code GraphProvider} could not
     * initialise its engine (e.g., missing backend driver, connection refused,
     * or insufficient off-heap budget for slab pools).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisCommunity/JdbcGraph"})</li>
     *   <li>index 1 – {@code String} reason       (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5001 = "EX-GRPH-5001";

    /**
     * Graph query execution failure: traversal, MATCH, or CRUD operation failed.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} queryType (e.g. "BFS", "MATCH", "SHORTEST_PATH")</li>
     *   <li>index 1 – {@code String} detail    (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5002 = "EX-GRPH-5002";

    /**
     * Graph dual-write sync failure: relational change could not be reflected
     * in the graph structure.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} edgeType (e.g. "FOLLOWS", "SIMILAR_TO")</li>
     *   <li>index 1 – {@code String} detail   (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5003 = "EX-GRPH-5003";

    /**
     * Path not found: shortest-path algorithm failed to reach target node.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code java.util.UUID} sourceNodeId</li>
     *   <li>index 1 – {@code java.util.UUID} targetNodeId</li>
     * </ul>
     */
    public static final String EX_GRPH_5004 = "EX-GRPH-5004";

    /**
     * Excessive allocation detected: graph driver exceeded pre-defined churn threshold.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} driverName</li>
     *   <li>index 1 – {@code long}   bytesAllocated</li>
     *   <li>index 2 – {@code long}   bytesTransferred</li>
     * </ul>
     */
    public static final String EX_GRPH_5005 = "EX-GRPH-5005";

    // -----------------------------------------------------------------------
    // EX-EVENT – Event Engine subsystem (L3 Logic Engines)
    // -----------------------------------------------------------------------

    /**
     * Generic event engine failure (no specific category).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} message (human-readable diagnostic)</li>
     * </ul>
     */
    public static final String EX_EVENT_6001 = "EX-EVENT-6001";

    /**
     * Event bus publish failure: queue is full and the implementation cannot accept the event.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} eventType   (event type name)</li>
     *   <li>index 1 – {@code long}   queueDepth  (current depth when overflow occurred)</li>
     *   <li>index 2 – {@code long}   queueCapacity</li>
     * </ul>
     */
    public static final String EX_EVENT_6002 = "EX-EVENT-6002";

    /**
     * Event registry conflict: an event type was registered twice with different settings.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} eventType  (conflicting type name)</li>
     *   <li>index 1 – {@code int}    ordinal     (ordinal that is already in use)</li>
     * </ul>
     */
    public static final String EX_EVENT_6003 = "EX-EVENT-6003";

    /**
     * Event provider creation failure: the {@code EventProvider} could not create an engine
     * from the given configuration.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason (static failure description)</li>
     * </ul>
     */
    public static final String EX_EVENT_6004 = "EX-EVENT-6004";

    // -----------------------------------------------------------------------
    // EX-FLOW – Flow Engine / Saga Orchestration subsystem
    // -----------------------------------------------------------------------

    /**
     * Flow provider engine creation failure: the {@code FlowProvider} could not create
     * an engine from the given configuration.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason (static failure description)</li>
     * </ul>
     */
    public static final String EX_FLOW_7001 = "EX-FLOW-7001";

    /**
     * Flow engine lifecycle failure: start, stop, compile, or scheduler operation failed.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} engineName</li>
     *   <li>index 1 – {@code String} phase — one of: {@code "START"}, {@code "STOP"},
     *       {@code "COMPILE"}, {@code "SCHEDULE"}</li>
     *   <li>index 2 – {@code String} staticReasonCode — e.g. {@code "STARTUP_FAILED"},
     *       {@code "COMPILE_FAILED"}, {@code "QUEUE_FULL"}</li>
     *   <li>index 3 – {@code int}    contextValue — phase-specific numeric context
     *       (queue depth for SCHEDULE); {@code -1} when not applicable</li>
     * </ul>
     */
    public static final String EX_FLOW_7002 = "EX-FLOW-7002";

    /**
     * Flow step execution failure: a step returned FAIL or threw an unrecoverable exception.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} definitionName</li>
     *   <li>index 1 – {@code long}   instanceIdMost</li>
     *   <li>index 2 – {@code long}   instanceIdLeast</li>
     *   <li>index 3 – {@code int}    stepIndex</li>
     *   <li>index 4 – {@code String} staticReasonCode — e.g. {@code "STEP_FAILED"},
     *       {@code "COMPENSATION_FAILED"}</li>
     *   <li>index 5 – {@code String} causeType — {@code cause.getClass().getName()}
     *       or {@code "none"}; class names are stable and not user-controlled</li>
     * </ul>
     */
    public static final String EX_FLOW_7003 = "EX-FLOW-7003";

    /**
     * Flow registry conflict: a step or transition was registered with a duplicate or
     * unknown identifier.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    stepId</li>
     *   <li>index 1 – {@code String} reason</li>
     * </ul>
     */
    public static final String EX_FLOW_7004 = "EX-FLOW-7004";

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
